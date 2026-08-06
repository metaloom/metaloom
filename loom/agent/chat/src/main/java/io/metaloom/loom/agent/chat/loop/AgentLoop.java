package io.metaloom.loom.agent.chat.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.ai.genai.llm.ChatMessage;
import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.ai.genai.llm.ToolDefinition;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.loom.agent.chat.AgentLoopDeps;
import io.metaloom.loom.agent.chat.AgentRequest;
import io.metaloom.loom.agent.chat.event.AgentEvent;
import io.metaloom.loom.agent.chat.event.AgentEventSink;
import io.metaloom.loom.agent.chat.event.AgentEventType;
import io.metaloom.loom.agent.chat.prompt.SystemPromptBuilder;
import io.metaloom.loom.agent.chat.ref.ReferenceExtractor;
import io.metaloom.loom.agent.chat.ref.VisualExtractor;
import io.metaloom.loom.agent.chat.skill.AgentSkill;
import io.metaloom.loom.agent.chat.skill.SkillPromptBuilder;
import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.agent.memory.tool.DeleteMemoryTool;
import io.metaloom.loom.agent.memory.tool.PutMemoryTool;
import io.metaloom.loom.agent.sandbox.SandboxOrchestrator;
import io.metaloom.loom.agent.sandbox.tool.CodingTool;
import io.metaloom.loom.agent.sandbox.tool.CodingTools;
import io.metaloom.loom.api.options.AiOptions;
import io.metaloom.loom.api.options.SandboxOptions;
import io.metaloom.loom.common.skill.BuiltinSkills;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionSkillPin;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The agentic loop of the chat agent (see CHAT.md §3.1).
 *
 * <p>One instance handles one {@code AgentRequest}: it replays the persisted transcript, streams LLM turns, dispatches tool calls in-process through the
 * {@link MCPToolRegistry} (permission-checked with the callers Vert.x user) and persists the resulting exchange back onto the chat. The loop is blocking and
 * must run on a worker thread; all outside communication goes through the {@link AgentEventSink}.</p>
 *
 * <p>Error rules follow pi: tool failures become <em>error tool results</em> so the model can react and the loop continues; LLM failures are terminal; hitting
 * the turn limit degrades gracefully into a final message synthesized from the accumulated state.</p>
 */
public class AgentLoop {

	private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

	/**
	 * Tool results are truncated to this length before they are persisted as {@code resultSummary}.
	 */
	public static final int RESULT_SUMMARY_MAX_LENGTH = 2048;

	private final AiOptions options;
	private final SandboxOptions sandboxOptions;
	private final ChatDao chatDao;
	private final ChatSessionDao chatSessionDao;
	private final SkillDao skillDao;
	private final GroupDao groupDao;
	private final MCPToolRegistry toolRegistry;
	private final TurnStreamer turnStreamer;
	private final AgentEventSink sink;
	private final AgentRequest request;
	private final SandboxOrchestrator sandbox;
	private final MemoryService memoryService;

	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final ReferenceExtractor referenceExtractor = new ReferenceExtractor();
	private final VisualExtractor visualExtractor = new VisualExtractor();

	private final StringBuilder contentBuffer = new StringBuilder();
	private final StringBuilder reasoningBuffer = new StringBuilder();
	private final JsonArray recordedToolCalls = new JsonArray();

	/**
	 * What the prompt discloses and {@code load_skill} can resolve: the built-in skills that ship with Loom, followed by the ones the user activated
	 * for this chat. Built-ins are always present, so a skill list is never empty.
	 */
	private List<AgentSkill> activeSkills = List.of();

	/**
	 * The stored subset of {@link #activeSkills}, kept separately because only a row can be pinned to a session — a built-in has no uuid and no
	 * version number to pin.
	 */
	private List<Skill> activeUserSkills = List.of();

	/** The caller's memory scopes and the header-only index, both resolved once per run. */
	private List<MemoryScopeRef> memoryScopes = List.of();

	private List<MemoryEntry> memoryIndex = List.of();

	/** Writes/deletes performed against memory during this run, bounded so a stuck loop cannot rewrite the same note forever. */
	private int memoryWrites = 0;

	/**
	 * The server-resolved caller identity handed to identity-scoped MCP tools. Built once per run in {@link #run()} — never derived from tool arguments.
	 */
	private MCPCallerContext callerContext = MCPCallerContext.ANONYMOUS;

	public AgentLoop(AiOptions options, SandboxOptions sandboxOptions, AgentLoopDeps deps, TurnStreamer turnStreamer, AgentEventSink sink,
		AgentRequest request) {
		this.options = options;
		this.sandboxOptions = sandboxOptions;
		this.chatDao = deps.chatDao();
		this.chatSessionDao = deps.chatSessionDao();
		this.skillDao = deps.skillDao();
		this.groupDao = deps.groupDao();
		this.toolRegistry = deps.toolRegistry();
		this.turnStreamer = turnStreamer;
		this.sink = sink;
		this.request = request;
		this.sandbox = deps.sandbox();
		this.memoryService = deps.memoryService();
	}

	/**
	 * Request the loop to stop. Checked between turns and between tool calls.
	 */
	public void abort() {
		cancelled.set(true);
	}

	/**
	 * Run the loop to completion. Blocking — must be called from a worker thread.
	 */
	public void run() {
		Chat chat = chatDao.load(request.chatUuid());
		if (chat == null) {
			emitError("NOT_FOUND", "Chat not found", true);
			emit(AgentEventType.AGENT_END, new JsonObject().put("chatUuid", request.chatUuid().toString()).put("status", "error"));
			return;
		}

		callerContext = buildCallerContext(chat);
		loadMemory();
		activeUserSkills = loadActiveSkills();
		activeSkills = Stream.concat(
			BuiltinSkills.list().stream().map(AgentSkill::of),
			activeUserSkills.stream().map(AgentSkill::of))
			.toList();
		boolean firstExchange = chat.getMessages() == null || chat.getMessages().isEmpty();
		List<ChatMessage> history = buildHistory(chat);
		List<ToolDefinition> tools = buildTools();
		LargeLanguageModel model = model();

		emit(AgentEventType.AGENT_START, new JsonObject()
			.put("chatUuid", chat.getUuid().toString())
			.put("model", model.id())
			.put("maxTurns", options.getMaxTurns()));

		String status = "completed";
		try {
			status = runTurns(history, tools, model);
		} catch (Exception e) {
			log.error("Agent run for chat {} failed", request.chatUuid(), e);
			emitError("LLM_ERROR", String.valueOf(e.getMessage()), true);
			status = "error";
		}

		JsonObject assistantMessage = persist(chat, status);
		if (!"error".equals(status)) {
			emit(AgentEventType.MESSAGE_END, new JsonObject().put("message", assistantMessage));
		}
		if ("completed".equals(status) && firstExchange && options.isTitleGeneration()) {
			generateTitle(chat, model);
		}
		emit(AgentEventType.AGENT_END, new JsonObject()
			.put("chatUuid", chat.getUuid().toString())
			.put("status", status));
	}

	/**
	 * Auto-title the chat after its first completed exchange. Failures are silent — the title simply stays.
	 */
	private void generateTitle(Chat chat, LargeLanguageModel model) {
		try {
			String instruction = "Produce a short title (at most 6 words) for the following conversation. Only output the title.\n\n"
				+ "user: " + request.message() + "\nassistant: " + truncate(contentBuffer.toString());
			LLMContext ctx = LLMContext.ctx(List.of(ChatMessage.user(instruction)), model, new PromptImpl(instruction));
			String title = turnStreamer.completeText(ctx);
			if (title == null || title.isBlank()) {
				return;
			}
			title = title.strip().replaceAll("^[\"']|[\"']$", "");
			if (title.length() > 80) {
				title = title.substring(0, 80);
			}
			chat.setTitle(title);
			chatDao.update(chat);
			emit(AgentEventType.TITLE, new JsonObject().put("title", title));

			// The session name defaults to the title; generate a short description too, then capture the
			// chat as a chat_session so it can be published / reused. All best-effort.
			String description = generateDescription(model);
			captureSession(chat, title, description);
		} catch (Exception e) {
			log.warn("Title generation for chat {} failed", chat.getUuid(), e);
		}
	}

	/**
	 * Generate a one-sentence session description from the first exchange. Returns {@code null} on any
	 * failure — the caller falls back to no description.
	 */
	private String generateDescription(LargeLanguageModel model) {
		try {
			String instruction = "Write a single-sentence description (at most 25 words) of what this chat session is about, "
				+ "suitable for a shared library listing. Only output the description.\n\n"
				+ "user: " + request.message() + "\nassistant: " + truncate(contentBuffer.toString());
			LLMContext ctx = LLMContext.ctx(List.of(ChatMessage.user(instruction)), model, new PromptImpl(instruction));
			String description = turnStreamer.completeText(ctx);
			if (description == null || description.isBlank()) {
				return null;
			}
			description = description.strip().replaceAll("^[\"']|[\"']$", "");
			if (description.length() > 512) {
				description = description.substring(0, 512);
			}
			return description;
		} catch (Exception e) {
			log.warn("Description generation for chat {} failed", request.chatUuid(), e);
			return null;
		}
	}

	/**
	 * Capture the chat as a {@code chat_session} the first time it is utilized, pinning the active skill
	 * versions. Idempotent — an existing session for the chat is left untouched. Best-effort.
	 */
	private void captureSession(Chat chat, String name, String description) {
		try {
			if (chatSessionDao.loadByChat(chat.getUuid()) != null) {
				return;
			}
			ChatSession session = chatSessionDao.createChatSession(request.userUuid(), name, description);
			session.setChatUuid(chat.getUuid());
			chatSessionDao.store(session);

			// Only stored skills are pinned. A built-in has no uuid and no version to pin, and it is
			// active on every run anyway, so recording it would say nothing about this session.
			if (!activeUserSkills.isEmpty()) {
				List<ChatSessionSkillPin> pins = activeUserSkills.stream()
					.map(s -> new ChatSessionSkillPin(session.getUuid(), s.getUuid(), s.getActiveVersionNumber()))
					.toList();
				chatSessionDao.replaceSkillPins(session.getUuid(), pins);
			}
		} catch (Exception e) {
			log.warn("Session capture for chat {} failed", chat.getUuid(), e);
		}
	}

	private String runTurns(List<ChatMessage> history, List<ToolDefinition> tools, LargeLanguageModel model) {
		for (int turn = 1; turn <= options.getMaxTurns(); turn++) {
			if (cancelled.get()) {
				return "aborted";
			}
			final int turnNo = turn;
			emit(AgentEventType.TURN_START, new JsonObject().put("turn", turnNo));

			LLMContext ctx = LLMContext.ctx(history, model, new PromptImpl(request.message()));
			ctx.setTools(tools);
			if (options.isThinkEnabled()) {
				ctx.enableThink();
			}

			TurnResult result = turnStreamer.streamTurn(ctx, new TurnListener() {
				@Override
				public void onTextDelta(String text) {
					contentBuffer.append(text);
					emit(AgentEventType.TEXT_DELTA, new JsonObject().put("turn", turnNo).put("text", text));
				}

				@Override
				public void onReasoningDelta(String text) {
					reasoningBuffer.append(text);
					emit(AgentEventType.REASONING_DELTA, new JsonObject().put("turn", turnNo).put("text", text));
				}
			});

			if (cancelled.get()) {
				emit(AgentEventType.TURN_END, new JsonObject().put("turn", turnNo));
				return "aborted";
			}

			if (!result.hasToolCalls()) {
				emit(AgentEventType.TURN_END, new JsonObject().put("turn", turnNo));
				return "completed";
			}

			history.add(ChatMessage.assistantWithToolCalls(result.toolCalls()));
			int callNo = 0;
			for (ToolCall call : result.toolCalls()) {
				if (cancelled.get()) {
					emit(AgentEventType.TURN_END, new JsonObject().put("turn", turnNo));
					return "aborted";
				}
				String callId = call.id() != null ? call.id() : "call-" + turnNo + "-" + callNo;
				callNo++;
				history.add(executeToolCall(turnNo, callId, call));
			}
			emit(AgentEventType.TURN_END, new JsonObject().put("turn", turnNo));
		}

		emitError("TURN_LIMIT", "The agent reached the maximum of " + options.getMaxTurns() + " turns without a final answer.", false);
		return "completed";
	}

	private ChatMessage executeToolCall(int turn, String callId, ToolCall call) {
		String name = call.name();
		JsonObject args = call.arguments() != null ? call.arguments() : new JsonObject();
		emit(AgentEventType.TOOL_START, new JsonObject()
			.put("turn", turn)
			.put("toolCallId", callId)
			.put("name", name)
			.put("args", args));

		long start = System.currentTimeMillis();
		boolean isError = false;
		String resultText;
		JsonArray refs = new JsonArray();
		JsonArray visuals = new JsonArray();

		if (SkillPromptBuilder.LOAD_SKILL_TOOL.equals(name)) {
			String skillName = args.getString("name");
			AgentSkill skill = activeSkills.stream()
				.filter(s -> s.name().equals(skillName))
				.findFirst()
				.orElse(null);
			if (skill == null) {
				isError = true;
				resultText = "ERROR: Unknown or inactive skill: " + skillName;
			} else {
				resultText = skill.content();
			}
		} else if (CodingTools.NAMES.contains(name)) {
			// Coding tools run inside this chat's isolated Session Runner (provisioned on first use),
			// keyed by the chat uuid. pi rule: a failed tool becomes an error result so the loop continues.
			try {
				JsonObject result = sandbox.dispatchCodingTool(request.chatUuid().toString(), name, args);
				resultText = formatCodingResult(name, result);
			} catch (Exception e) {
				log.warn("Coding tool {} failed", name, e);
				isError = true;
				resultText = "ERROR: " + e.getMessage();
			}
		} else if (memoryWriteBudgetExhausted(name)) {
			isError = true;
			resultText = "ERROR: This run has reached its limit of " + memoryService.cfg().getMaxWritesPerRun()
				+ " memory writes. Stop writing to memory and answer with what you have.";
		} else {
			try {
				JsonObject toolResult = toolRegistry.dispatch(name, args, request.user(), callerContext)
					.toCompletionStage()
					.toCompletableFuture()
					.get(options.getToolTimeoutMs(), TimeUnit.MILLISECONDS);
				resultText = extractTextContent(toolResult);
				refs = referenceExtractor.extract(toolResult);
				visuals = visualExtractor.extract(toolResult);
			} catch (Exception e) {
				// pi rule: tool failures become error tool RESULTS — the loop continues so the model can react
				log.warn("Tool call {} failed", name, e);
				isError = true;
				resultText = "ERROR: " + e.getMessage();
			}
		}

		long duration = System.currentTimeMillis() - start;
		String summary = truncate(resultText);
		emit(AgentEventType.TOOL_END, new JsonObject()
			.put("turn", turn)
			.put("toolCallId", callId)
			.put("name", name)
			.put("isError", isError)
			.put("summary", summary)
			.put("references", refs)
			.put("visuals", visuals));

		recordedToolCalls.add(new JsonObject()
			.put("id", callId)
			.put("name", name)
			.put("args", args)
			.put("resultSummary", summary)
			.put("isError", isError)
			.put("durationMs", duration));

		return ChatMessage.toolResult(callId, name, resultText);
	}

	private JsonObject persist(Chat chat, String status) {
		JsonObject userMessage = new JsonObject()
			.put("id", UUID.randomUUID().toString())
			.put("role", "user")
			.put("content", request.message())
			.put("skillUuids", new JsonArray(request.skillUuids().stream().map(UUID::toString).map(Object.class::cast).toList()))
			.put("createdAt", Instant.now().toString());

		JsonObject assistantMessage = new JsonObject()
			.put("id", UUID.randomUUID().toString())
			.put("role", "assistant")
			.put("content", contentBuffer.toString())
			.put("createdAt", Instant.now().toString());
		if (!reasoningBuffer.isEmpty()) {
			assistantMessage.put("reasoning", reasoningBuffer.toString());
		}
		if (!recordedToolCalls.isEmpty()) {
			assistantMessage.put("toolCalls", recordedToolCalls);
		}
		if (!referenceExtractor.references().isEmpty()) {
			assistantMessage.put("references", referenceExtractor.references());
		}
		// Visuals are persisted with the message so a reloaded transcript still shows the diagrams; they are
		// never replayed into the LLM history (buildHistory reads content and toolCalls only).
		if (!visualExtractor.visuals().isEmpty()) {
			assistantMessage.put("visuals", visualExtractor.visuals());
		}

		JsonArray messages = chat.getMessages() != null ? chat.getMessages() : new JsonArray();
		messages.add(userMessage);
		// On terminal errors only the user message is persisted — the transcript stays consistent for a retry
		if (!"error".equals(status)) {
			messages.add(assistantMessage);
		}
		chat.setMessages(messages);

		JsonObject meta = chat.getMeta() != null ? chat.getMeta() : new JsonObject();
		meta.put("activeSkillUuids", new JsonArray(request.skillUuids().stream().map(UUID::toString).map(Object.class::cast).toList()));
		meta.put("model", options.getModelId());
		if ("error".equals(status)) {
			meta.put("lastError", Instant.now().toString());
		} else {
			meta.remove("lastError");
		}
		chat.setMeta(meta);
		chat.setEditorUuid(request.userUuid());
		chat.setEdited(Instant.now());
		chatDao.update(chat);

		return assistantMessage;
	}

	/**
	 * Resolve the caller identity handed to identity-scoped MCP tools.
	 *
	 * <p>Everything here comes from the server: the loom user of the request, the groups that user belongs to, and the space of the chat. Tool arguments
	 * never contribute — they may only filter over what this resolves to. Group lookup failures degrade to "no groups" rather than failing the run.</p>
	 */
	private MCPCallerContext buildCallerContext(Chat chat) {
		Set<UUID> groupUuids = Set.of();
		if (groupDao != null && request.userUuid() != null) {
			try {
				groupUuids = groupDao.loadGroupsForUser(request.userUuid()).stream()
					.map(Group::getUuid)
					.collect(Collectors.toSet());
			} catch (Exception e) {
				log.warn("Could not resolve groups for user {} — continuing without group scopes", request.userUuid(), e);
			}
		}
		return new MCPCallerContext(request.userUuid(), userName(), groupUuids, chat.getSpaceUuid(), chat.getUuid());
	}

	/**
	 * Username of the caller, used only for provenance stamps. Null when auth is disabled.
	 */
	private String userName() {
		return request.user() != null ? request.user().principal().getString("username") : null;
	}

	/**
	 * Resolve the caller's memory scopes and load the header-only index once per run.
	 *
	 * <p>Memory is an enhancement, never a precondition: any failure here degrades to "no memory" and the run continues.</p>
	 */
	private void loadMemory() {
		if (memoryService == null || !memoryService.isEnabled()) {
			return;
		}
		try {
			memoryScopes = memoryService.scopes().resolve(callerContext);
			memoryIndex = memoryService.index(memoryScopes, memoryService.cfg().getPromptMaxEntries());
		} catch (Exception e) {
			log.warn("Could not load the memory index for chat {} — continuing without memory context", request.chatUuid(), e);
			memoryScopes = List.of();
			memoryIndex = List.of();
		}
	}

	/**
	 * Whether this run may perform another memory write.
	 *
	 * <p>Exceeding the budget becomes an error tool result rather than aborting: a model looping on put_memory should be told to stop, not kill the run.</p>
	 */
	private boolean memoryWriteBudgetExhausted(String toolName) {
		if (memoryService == null || !isMemoryWriteTool(toolName)) {
			return false;
		}
		return ++memoryWrites > memoryService.cfg().getMaxWritesPerRun();
	}

	private static boolean isMemoryWriteTool(String toolName) {
		return PutMemoryTool.NAME.equals(toolName) || DeleteMemoryTool.NAME.equals(toolName);
	}

	private List<Skill> loadActiveSkills() {
		if (request.skillUuids().isEmpty()) {
			return List.of();
		}
		// Deleted uuids are silently dropped; foreign or disabled skills must never influence the run
		return skillDao.loadByUuids(request.skillUuids()).stream()
			.filter(skill -> request.userUuid().equals(skill.getCreatorUuid()))
			.filter(Skill::isEnabled)
			.toList();
	}

	private List<ChatMessage> buildHistory(Chat chat) {
		List<ChatMessage> history = new ArrayList<>();
		history.add(ChatMessage.system(SystemPromptBuilder.build(activeSkills, memoryService, memoryScopes, memoryIndex, sandboxOptions.isEnabled())));

		// Replay the persisted transcript. Tool results are reconstructed from the truncated
		// resultSummary — an accepted context fidelity trade-off (CHAT.md §4.3).
		JsonArray messages = chat.getMessages() != null ? chat.getMessages() : new JsonArray();
		for (int i = 0; i < messages.size(); i++) {
			JsonObject msg = messages.getJsonObject(i);
			String role = msg.getString("role");
			String content = msg.getString("content");
			if ("user".equals(role)) {
				if (content != null && !content.isBlank()) {
					history.add(ChatMessage.user(content));
				}
			} else if ("assistant".equals(role)) {
				JsonArray toolCalls = msg.getJsonArray("toolCalls");
				if (toolCalls != null && !toolCalls.isEmpty()) {
					List<ToolCall> calls = new ArrayList<>();
					for (int c = 0; c < toolCalls.size(); c++) {
						JsonObject tc = toolCalls.getJsonObject(c);
						calls.add(new ToolCall(tc.getString("id"), tc.getString("name"), tc.getJsonObject("args", new JsonObject())));
					}
					history.add(ChatMessage.assistantWithToolCalls(calls));
					for (int c = 0; c < toolCalls.size(); c++) {
						JsonObject tc = toolCalls.getJsonObject(c);
						history.add(ChatMessage.toolResult(tc.getString("id"), tc.getString("name"), tc.getString("resultSummary", "")));
					}
				}
				if (content != null && !content.isBlank()) {
					history.add(ChatMessage.assistant(content));
				}
			}
		}

		history.add(ChatMessage.user(request.message()));
		return history;
	}

	private List<ToolDefinition> buildTools() {
		List<ToolDefinition> tools = new ArrayList<>();
		// Only what this caller may actually invoke. Advertising a tool the user has no permission
		// for costs a turn (the model calls it, gets a permission error back as a tool *result*, and
		// often tries again) and, worse, puts the capability in the prompt: a create_pipeline the
		// user may not use still reads to the model as an invitation to author one.
		for (MCPToolDescriptor descriptor : permittedTools()) {
			tools.add(new ToolDefinition(descriptor.name(), descriptor.description(), descriptor.inputSchema()));
		}
		// Coding tools are executed inside a per-chat isolated sandbox (see executeToolCall). Only
		// advertised when the sandbox is enabled by configuration.
		if (sandboxOptions.isEnabled()) {
			for (CodingTool tool : CodingTools.definitions()) {
				tools.add(new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()));
			}
		}
		if (!activeSkills.isEmpty()) {
			tools.add(new ToolDefinition(
				SkillPromptBuilder.LOAD_SKILL_TOOL,
				"Load the full instructions of an active skill by its name. Always load a skill before applying it.",
				MCPToolDescriptor.buildInputSchema(List.of(
					new MCPToolParam("name", "string", "The name of the skill to load", true)))));
		}
		return tools;
	}

	/**
	 * The MCP tools this run's caller is permitted to invoke.
	 *
	 * <p>
	 * Resolving permissions is a database lookup, so it is awaited once here rather than per tool; the loop already runs on a worker thread. The
	 * registry itself degrades to the tools that need no permission when the authorization lookup fails; this catch covers the coarser case — the
	 * lookup timing out or the call throwing — and then advertises nothing. Both fail closed, because an agent that can do less is better than one
	 * offering a capability it will be refused on. The run continues either way: the model still has its skills and, when enabled, the coding tools.
	 * </p>
	 */
	private List<MCPToolDescriptor> permittedTools() {
		try {
			return toolRegistry.listDescriptorsFor(request.user())
				.toCompletionStage()
				.toCompletableFuture()
				.get(options.getToolTimeoutMs(), TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			log.warn("Could not resolve the permitted MCP tools for this run", e);
			return List.of();
		}
	}

	private LargeLanguageModel model() {
		return new LargeLanguageModel() {
			@Override
			public String id() {
				return options.getModelId();
			}

			@Override
			public String url() {
				return options.getUrl();
			}

			@Override
			public long contextWindow() {
				return options.getContextWindow();
			}
		};
	}

	private void emitError(String code, String message, boolean terminal) {
		emit(AgentEventType.ERROR, new JsonObject()
			.put("code", code)
			.put("message", message)
			.put("terminal", terminal));
	}

	private void emit(AgentEventType type, JsonObject data) {
		sink.emit(AgentEvent.of(type, data));
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= RESULT_SUMMARY_MAX_LENGTH ? text : text.substring(0, RESULT_SUMMARY_MAX_LENGTH);
	}

	/**
	 * Render a coding tool result into the text the model sees. A non-zero shell exit is a normal
	 * result (not an error) so the model can react to it — mirrors pi.
	 */
	private static String formatCodingResult(String name, JsonObject result) {
		if (result == null) {
			return "";
		}
		if (CodingTools.RUN_SHELL.equals(name)) {
			StringBuilder sb = new StringBuilder();
			sb.append(result.getString("output", ""));
			sb.append("\n[exit code ").append(result.getInteger("exitCode", -1)).append("]");
			if (result.getBoolean("timedOut", false)) {
				sb.append("\n[command timed out]");
			}
			if (result.getBoolean("truncated", false)) {
				sb.append("\n[output truncated; full output at ").append(result.getValue("fullOutputPath")).append("]");
			}
			return sb.toString();
		}
		if (CodingTools.READ_FILE.equals(name)) {
			String content = result.getString("content", "");
			return result.getBoolean("truncated", false) ? content + "\n[truncated]" : content;
		}
		// write_file / list_files and any other: return the raw JSON result.
		return result.encode();
	}

	private static String extractTextContent(JsonObject result) {
		if (result == null) {
			return "";
		}
		JsonArray content = result.getJsonArray("content");
		if (content == null || content.isEmpty()) {
			return result.encode();
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < content.size(); i++) {
			JsonObject item = content.getJsonObject(i);
			if ("text".equals(item.getString("type"))) {
				if (!sb.isEmpty()) {
					sb.append("\n");
				}
				sb.append(item.getString("text", ""));
			}
		}
		return sb.toString();
	}

}
