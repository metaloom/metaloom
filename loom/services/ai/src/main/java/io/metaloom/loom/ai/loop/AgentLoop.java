package io.metaloom.loom.ai.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.ai.genai.llm.ChatMessage;
import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.LLMProviderType;
import io.metaloom.ai.genai.llm.LargeLanguageModel;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.ai.genai.llm.ToolDefinition;
import io.metaloom.ai.genai.llm.prompt.impl.PromptImpl;
import io.metaloom.loom.ai.AgentRequest;
import io.metaloom.loom.ai.event.AgentEvent;
import io.metaloom.loom.ai.event.AgentEventSink;
import io.metaloom.loom.ai.event.AgentEventType;
import io.metaloom.loom.ai.ref.ReferenceExtractor;
import io.metaloom.loom.ai.skill.SkillPromptBuilder;
import io.metaloom.loom.api.options.AiOptions;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
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
	private final ChatDao chatDao;
	private final SkillDao skillDao;
	private final MCPToolRegistry toolRegistry;
	private final TurnStreamer turnStreamer;
	private final AgentEventSink sink;
	private final AgentRequest request;

	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final ReferenceExtractor referenceExtractor = new ReferenceExtractor();

	private final StringBuilder contentBuffer = new StringBuilder();
	private final StringBuilder reasoningBuffer = new StringBuilder();
	private final JsonArray recordedToolCalls = new JsonArray();

	private List<Skill> activeSkills = List.of();

	public AgentLoop(AiOptions options, ChatDao chatDao, SkillDao skillDao, MCPToolRegistry toolRegistry, TurnStreamer turnStreamer,
		AgentEventSink sink, AgentRequest request) {
		this.options = options;
		this.chatDao = chatDao;
		this.skillDao = skillDao;
		this.toolRegistry = toolRegistry;
		this.turnStreamer = turnStreamer;
		this.sink = sink;
		this.request = request;
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

		activeSkills = loadActiveSkills();
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
		} catch (Exception e) {
			log.warn("Title generation for chat {} failed", chat.getUuid(), e);
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

		if (SkillPromptBuilder.LOAD_SKILL_TOOL.equals(name)) {
			String skillName = args.getString("name");
			Skill skill = activeSkills.stream()
				.filter(s -> s.getName().equals(skillName))
				.findFirst()
				.orElse(null);
			if (skill == null) {
				isError = true;
				resultText = "ERROR: Unknown or inactive skill: " + skillName;
			} else {
				resultText = skill.getContent();
			}
		} else {
			try {
				JsonObject toolResult = toolRegistry.dispatch(name, args, request.user())
					.toCompletionStage()
					.toCompletableFuture()
					.get(options.getToolTimeoutMs(), TimeUnit.MILLISECONDS);
				resultText = extractTextContent(toolResult);
				refs = referenceExtractor.extract(toolResult);
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
			.put("references", refs));

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
		history.add(ChatMessage.system(SkillPromptBuilder.build(activeSkills)));

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
		for (MCPToolDescriptor descriptor : toolRegistry.listDescriptors()) {
			tools.add(new ToolDefinition(descriptor.name(), descriptor.description(), descriptor.inputSchema()));
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

			@Override
			public LLMProviderType providerType() {
				return LLMProviderType.valueOf(options.getProviderType().toUpperCase());
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
