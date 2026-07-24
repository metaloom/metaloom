package io.metaloom.loom.agent.chat.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.LLMContext;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.loom.agent.chat.AgentRequest;
import io.metaloom.loom.agent.chat.event.AgentEvent;
import io.metaloom.loom.agent.chat.event.AgentEventType;
import io.metaloom.loom.agent.chat.skill.SkillPromptBuilder;
import io.metaloom.loom.agent.sandbox.SandboxOrchestrator;
import io.metaloom.loom.api.options.AiOptions;
import io.metaloom.loom.api.options.SandboxOptions;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Unit tests for the agentic loop using a scripted {@link TurnStreamer} — no live LLM involved.
 */
public class AgentLoopTest {

	private static final UUID CHAT_UUID = UUID.randomUUID();
	private static final UUID USER_UUID = UUID.randomUUID();

	private ChatDao chatDao;
	private ChatSessionDao chatSessionDao;
	private SkillDao skillDao;
	private MCPToolRegistry toolRegistry;
	private Chat chat;
	private AtomicReference<JsonArray> persistedMessages;
	private AtomicReference<JsonObject> persistedMeta;
	private List<AgentEvent> events;

	@BeforeEach
	public void setup() {
		chatDao = mock(ChatDao.class);
		chatSessionDao = mock(ChatSessionDao.class);
		skillDao = mock(SkillDao.class);
		toolRegistry = mock(MCPToolRegistry.class);
		when(toolRegistry.listDescriptors()).thenReturn(List.of(searchAssetsDescriptor()));
		when(skillDao.loadByUuids(anyList())).thenReturn(List.of());

		chat = mock(Chat.class);
		persistedMessages = new AtomicReference<>(new JsonArray());
		persistedMeta = new AtomicReference<>(null);
		when(chat.getUuid()).thenReturn(CHAT_UUID);
		when(chat.getCreatorUuid()).thenReturn(USER_UUID);
		when(chat.getMessages()).thenAnswer(i -> persistedMessages.get());
		when(chat.setMessages(any())).thenAnswer(i -> {
			persistedMessages.set(i.getArgument(0));
			return chat;
		});
		when(chat.getMeta()).thenAnswer(i -> persistedMeta.get());
		when(chat.setMeta(any())).thenAnswer(i -> {
			persistedMeta.set(i.getArgument(0));
			return chat;
		});
		when(chatDao.load(CHAT_UUID)).thenReturn(chat);

		events = new ArrayList<>();
	}

	private static MCPToolDescriptor searchAssetsDescriptor() {
		return new MCPToolDescriptor("search_assets", "Search assets",
			MCPToolDescriptor.buildInputSchema(List.of(new MCPToolDescriptor.MCPToolParam("query", "string", "query", false))),
			List.of("READ_ASSET"));
	}

	private AgentLoop loop(AiOptions options, TurnStreamer streamer, List<UUID> skillUuids) {
		AgentRequest request = new AgentRequest(CHAT_UUID, USER_UUID, null, "Find beach videos", skillUuids);
		// Sandbox disabled by default — coding tools are not advertised and the orchestrator is never called.
		return new AgentLoop(options, new SandboxOptions(), chatDao, chatSessionDao, skillDao, toolRegistry, streamer, events::add, request,
			mock(SandboxOrchestrator.class));
	}

	private List<AgentEventType> eventTypes() {
		return events.stream().map(AgentEvent::type).toList();
	}

	private AgentEvent firstEvent(AgentEventType type) {
		return events.stream().filter(e -> e.type() == type).findFirst().orElse(null);
	}

	/**
	 * Scripted streamer: each call pops the next result and replays its deltas.
	 */
	private static TurnStreamer scripted(List<TurnResult> results) {
		Deque<TurnResult> queue = new ArrayDeque<>(results);
		return (ctx, listener) -> {
			TurnResult result = queue.isEmpty() ? new TurnResult("done", null, List.of()) : queue.pop();
			if (result.reasoning() != null) {
				listener.onReasoningDelta(result.reasoning());
			}
			if (result.text() != null && !result.text().isBlank()) {
				listener.onTextDelta(result.text());
			}
			return result;
		};
	}

	@Test
	public void testToolLoopEventSequenceAndPersistence() {
		when(toolRegistry.dispatch(eq("search_assets"), any(), any())).thenReturn(Future.succeededFuture(new JsonObject()
			.put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", "Found 1 asset")))
			.put("references", new JsonArray().add(new JsonObject().put("type", "asset").put("uuid", "a1").put("label", "beach.mp4")))));

		TurnStreamer streamer = scripted(List.of(
			new TurnResult(null, "The user wants beach videos.",
				List.of(new ToolCall("c1", "search_assets", new JsonObject().put("query", "beach")))),
			new TurnResult("I found **beach.mp4**.", null, List.of())));

		loop(new AiOptions(), streamer, List.of()).run();

		assertEquals(List.of(
			AgentEventType.AGENT_START,
			AgentEventType.TURN_START,
			AgentEventType.REASONING_DELTA,
			AgentEventType.TOOL_START,
			AgentEventType.TOOL_END,
			AgentEventType.TURN_END,
			AgentEventType.TURN_START,
			AgentEventType.TEXT_DELTA,
			AgentEventType.TURN_END,
			AgentEventType.MESSAGE_END,
			AgentEventType.AGENT_END), eventTypes());

		AgentEvent toolEnd = firstEvent(AgentEventType.TOOL_END);
		assertFalse(toolEnd.data().getBoolean("isError"));
		assertEquals(1, toolEnd.data().getJsonArray("references").size(), "The tool references should be emitted live");

		AgentEvent agentEnd = firstEvent(AgentEventType.AGENT_END);
		assertEquals("completed", agentEnd.data().getString("status"));

		// Persistence: user + assistant message appended, schema per CHAT.md §4.3
		JsonArray messages = persistedMessages.get();
		assertEquals(2, messages.size());
		JsonObject userMsg = messages.getJsonObject(0);
		assertEquals("user", userMsg.getString("role"));
		assertEquals("Find beach videos", userMsg.getString("content"));
		JsonObject assistantMsg = messages.getJsonObject(1);
		assertEquals("assistant", assistantMsg.getString("role"));
		assertEquals("I found **beach.mp4**.", assistantMsg.getString("content"));
		assertEquals("The user wants beach videos.", assistantMsg.getString("reasoning"));
		assertEquals(1, assistantMsg.getJsonArray("toolCalls").size());
		JsonObject recordedCall = assistantMsg.getJsonArray("toolCalls").getJsonObject(0);
		assertEquals("search_assets", recordedCall.getString("name"));
		assertEquals("Found 1 asset", recordedCall.getString("resultSummary"));
		assertFalse(recordedCall.getBoolean("isError"));
		assertEquals(1, assistantMsg.getJsonArray("references").size());
		assertNotNull(persistedMeta.get());
		assertNotNull(persistedMeta.get().getJsonArray("activeSkillUuids"));

		// The message_end payload carries the persisted assistant message
		AgentEvent messageEnd = firstEvent(AgentEventType.MESSAGE_END);
		assertEquals(assistantMsg.getString("content"), messageEnd.data().getJsonObject("message").getString("content"));
	}

	@Test
	public void testToolErrorBecomesErrorResultAndLoopContinues() {
		when(toolRegistry.dispatch(eq("search_assets"), any(), any())).thenReturn(Future.failedFuture("boom"));

		TurnStreamer streamer = scripted(List.of(
			new TurnResult(null, null, List.of(new ToolCall("c1", "search_assets", new JsonObject()))),
			new TurnResult("Sorry, the search failed.", null, List.of())));

		loop(new AiOptions(), streamer, List.of()).run();

		AgentEvent toolEnd = firstEvent(AgentEventType.TOOL_END);
		assertTrue(toolEnd.data().getBoolean("isError"), "The tool failure must be flagged");
		assertTrue(toolEnd.data().getString("summary").startsWith("ERROR:"));

		// The loop continued to a final answer — no terminal error event
		assertNull(firstEvent(AgentEventType.ERROR));
		assertEquals("completed", firstEvent(AgentEventType.AGENT_END).data().getString("status"));
		JsonObject assistantMsg = persistedMessages.get().getJsonObject(1);
		assertEquals("Sorry, the search failed.", assistantMsg.getString("content"));
		assertTrue(assistantMsg.getJsonArray("toolCalls").getJsonObject(0).getBoolean("isError"));
	}

	@Test
	public void testTurnLimit() {
		when(toolRegistry.dispatch(any(), any(), any())).thenReturn(Future.succeededFuture(new JsonObject()
			.put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", "result")))));

		AiOptions options = new AiOptions();
		options.setMaxTurns(3);
		// Every turn requests another tool call — the loop must degrade gracefully at the limit
		TurnStreamer streamer = (ctx, listener) -> new TurnResult(null, null,
			List.of(new ToolCall(null, "search_assets", new JsonObject())));

		loop(options, streamer, List.of()).run();

		AgentEvent error = firstEvent(AgentEventType.ERROR);
		assertNotNull(error, "The turn limit must surface as an error event");
		assertEquals("TURN_LIMIT", error.data().getString("code"));
		assertFalse(error.data().getBoolean("terminal"), "The turn limit is a graceful degradation, not a terminal failure");

		assertEquals(3, eventTypes().stream().filter(t -> t == AgentEventType.TURN_START).count());
		assertNotNull(firstEvent(AgentEventType.MESSAGE_END), "A final message must be synthesized");
		assertEquals("completed", firstEvent(AgentEventType.AGENT_END).data().getString("status"));
	}

	@Test
	public void testAbort() {
		AtomicReference<AgentLoop> loopRef = new AtomicReference<>();
		TurnStreamer streamer = (ctx, listener) -> {
			listener.onTextDelta("partial answer");
			// Simulate an abort arriving while the turn is in flight
			loopRef.get().abort();
			return new TurnResult("partial answer", null, List.of(new ToolCall("c1", "search_assets", new JsonObject())));
		};

		AgentLoop loop = loop(new AiOptions(), streamer, List.of());
		loopRef.set(loop);
		loop.run();

		assertEquals("aborted", firstEvent(AgentEventType.AGENT_END).data().getString("status"));
		// No tool may be dispatched after the abort
		assertNull(firstEvent(AgentEventType.TOOL_START));
		// The partial content is persisted
		assertEquals("partial answer", persistedMessages.get().getJsonObject(1).getString("content"));
	}

	@Test
	public void testLlmFailureIsTerminal() {
		TurnStreamer streamer = (ctx, listener) -> {
			throw new IllegalStateException("provider unreachable");
		};

		loop(new AiOptions(), streamer, List.of()).run();

		AgentEvent error = firstEvent(AgentEventType.ERROR);
		assertEquals("LLM_ERROR", error.data().getString("code"));
		assertTrue(error.data().getBoolean("terminal"));
		assertEquals("error", firstEvent(AgentEventType.AGENT_END).data().getString("status"));
		assertNull(firstEvent(AgentEventType.MESSAGE_END), "No final message on terminal errors");

		// Only the user message is persisted so the transcript stays consistent for a retry
		assertEquals(1, persistedMessages.get().size());
		assertEquals("user", persistedMessages.get().getJsonObject(0).getString("role"));
		assertNotNull(persistedMeta.get().getString("lastError"));
	}

	@Test
	public void testSkillPromptAndLoadSkillTool() {
		UUID skillUuid = UUID.randomUUID();
		Skill skill = mock(Skill.class);
		when(skill.getName()).thenReturn("transcript-summarizer");
		when(skill.getDescription()).thenReturn("Summarize transcripts");
		when(skill.getContent()).thenReturn("# Summarizer\nDo the thing.");
		when(skill.isEnabled()).thenReturn(true);
		when(skill.getCreatorUuid()).thenReturn(USER_UUID);
		when(skillDao.loadByUuids(anyList())).thenReturn(List.of(skill));

		AtomicReference<LLMContext> firstCtx = new AtomicReference<>();
		Deque<TurnResult> results = new ArrayDeque<>(List.of(
			new TurnResult(null, null, List.of(new ToolCall("c1", SkillPromptBuilder.LOAD_SKILL_TOOL,
				new JsonObject().put("name", "transcript-summarizer")))),
			new TurnResult("Summary done.", null, List.of())));
		TurnStreamer streamer = (ctx, listener) -> {
			firstCtx.compareAndSet(null, ctx);
			return results.pop();
		};

		loop(new AiOptions(), streamer, List.of(skillUuid)).run();

		// Progressive disclosure: name + description in the system prompt, content NOT
		String systemPrompt = firstCtx.get().chatHistory().get(0).getText();
		assertTrue(systemPrompt.contains("<available_skills>"));
		assertTrue(systemPrompt.contains("transcript-summarizer: Summarize transcripts"));
		assertFalse(systemPrompt.contains("Do the thing."), "The skill content must not be injected up front");

		// The load_skill tool is offered and returns the content
		assertTrue(firstCtx.get().tools().stream().anyMatch(t -> t.name().equals(SkillPromptBuilder.LOAD_SKILL_TOOL)));
		AgentEvent toolEnd = firstEvent(AgentEventType.TOOL_END);
		assertFalse(toolEnd.data().getBoolean("isError"));
		assertEquals("# Summarizer\nDo the thing.", toolEnd.data().getString("summary"));
	}

	@Test
	public void testForeignAndDisabledSkillsAreIgnored() {
		Skill foreign = mock(Skill.class);
		when(foreign.getCreatorUuid()).thenReturn(UUID.randomUUID());
		when(foreign.isEnabled()).thenReturn(true);
		Skill disabled = mock(Skill.class);
		when(disabled.getCreatorUuid()).thenReturn(USER_UUID);
		when(disabled.isEnabled()).thenReturn(false);
		when(skillDao.loadByUuids(anyList())).thenReturn(List.of(foreign, disabled));

		AtomicReference<LLMContext> firstCtx = new AtomicReference<>();
		TurnStreamer streamer = (ctx, listener) -> {
			firstCtx.compareAndSet(null, ctx);
			return new TurnResult("done", null, List.of());
		};

		loop(new AiOptions(), streamer, List.of(UUID.randomUUID(), UUID.randomUUID())).run();

		String systemPrompt = firstCtx.get().chatHistory().get(0).getText();
		assertFalse(systemPrompt.contains("<available_skills>"), "Foreign and disabled skills must not reach the prompt");
		assertTrue(firstCtx.get().tools().stream().noneMatch(t -> t.name().equals(SkillPromptBuilder.LOAD_SKILL_TOOL)),
			"Without active skills the load_skill tool must not be offered");
	}

	@Test
	public void testAutoTitleOnFirstExchange() {
		when(chat.setTitle(any())).thenReturn(chat);
		TurnStreamer streamer = new TurnStreamer() {
			@Override
			public TurnResult streamTurn(io.metaloom.ai.genai.llm.LLMContext ctx, TurnListener listener) {
				listener.onTextDelta("The answer.");
				return new TurnResult("The answer.", null, List.of());
			}

			@Override
			public String completeText(io.metaloom.ai.genai.llm.LLMContext ctx) {
				return "\"Beach footage review\"\n";
			}
		};

		loop(new AiOptions(), streamer, List.of()).run();

		AgentEvent title = firstEvent(AgentEventType.TITLE);
		assertNotNull(title, "The first completed exchange should emit a title event");
		assertEquals("Beach footage review", title.data().getString("title"), "The title must be stripped of quotes and whitespace");
		org.mockito.Mockito.verify(chat).setTitle("Beach footage review");

		// A second exchange on the now non-empty chat must not re-title
		events.clear();
		loop(new AiOptions(), streamer, List.of()).run();
		assertNull(firstEvent(AgentEventType.TITLE), "Only the first exchange triggers title generation");
	}

	@Test
	public void testTranscriptReplay() {
		// Pre-existing transcript with a tool-calling assistant message
		persistedMessages.get()
			.add(new JsonObject().put("role", "user").put("content", "earlier question"))
			.add(new JsonObject().put("role", "assistant").put("content", "earlier answer")
				.put("toolCalls", new JsonArray().add(new JsonObject()
					.put("id", "old1").put("name", "search_assets")
					.put("args", new JsonObject().put("query", "old"))
					.put("resultSummary", "old result"))));

		AtomicReference<LLMContext> firstCtx = new AtomicReference<>();
		TurnStreamer streamer = (ctx, listener) -> {
			firstCtx.compareAndSet(null, ctx);
			return new TurnResult("done", null, List.of());
		};

		loop(new AiOptions(), streamer, List.of()).run();

		var history = firstCtx.get().chatHistory();
		// system, user, assistant(toolCalls), tool result, assistant text, new user message
		assertEquals(6, history.size());
		assertEquals("system", history.get(0).getRole());
		assertEquals("earlier question", history.get(1).getText());
		assertEquals(1, history.get(2).getToolCalls().size());
		assertEquals("tool", history.get(3).getRole());
		assertEquals("old result", history.get(3).getText());
		assertEquals("earlier answer", history.get(4).getText());
		assertEquals("Find beach videos", history.get(5).getText());
	}

	// -- coding sandbox -----------------------------------------------------
	private AgentLoop sandboxLoop(SandboxOrchestrator sandbox, TurnStreamer streamer) {
		AgentRequest request = new AgentRequest(CHAT_UUID, USER_UUID, null, "Find beach videos", List.of());
		SandboxOptions enabled = new SandboxOptions().setEnabled(true);
		return new AgentLoop(new AiOptions(), enabled, chatDao, chatSessionDao, skillDao, toolRegistry, streamer, events::add, request, sandbox);
	}

	@Test
	public void testCodingToolIsInterceptedAndRunInSandbox() {
		SandboxOrchestrator sandbox = mock(SandboxOrchestrator.class);
		when(sandbox.dispatchCodingTool(eq(CHAT_UUID.toString()), eq("run_shell"), any()))
			.thenReturn(new JsonObject().put("exitCode", 0).put("output", "hello world").put("truncated", false));

		AtomicReference<LLMContext> firstCtx = new AtomicReference<>();
		Deque<TurnResult> results = new ArrayDeque<>(List.of(
			new TurnResult(null, null, List.of(new ToolCall("c1", "run_shell", new JsonObject().put("command", "echo hello world")))),
			new TurnResult("Done.", null, List.of())));
		TurnStreamer streamer = (ctx, listener) -> {
			firstCtx.compareAndSet(null, ctx);
			return results.pop();
		};

		sandboxLoop(sandbox, streamer).run();

		// The coding tool is advertised only when the sandbox is enabled.
		assertTrue(firstCtx.get().tools().stream().anyMatch(t -> t.name().equals("run_shell")), "run_shell must be offered");
		// It is routed to the sandbox (not the MCP registry) keyed by the chat uuid.
		org.mockito.Mockito.verify(sandbox).dispatchCodingTool(eq(CHAT_UUID.toString()), eq("run_shell"), any());

		AgentEvent toolEnd = firstEvent(AgentEventType.TOOL_END);
		assertFalse(toolEnd.data().getBoolean("isError"));
		assertTrue(toolEnd.data().getString("summary").contains("hello world"));
		assertTrue(toolEnd.data().getString("summary").contains("exit code 0"));
		assertEquals("completed", firstEvent(AgentEventType.AGENT_END).data().getString("status"));
	}

	@Test
	public void testCodingToolFailureBecomesErrorResultAndLoopContinues() {
		SandboxOrchestrator sandbox = mock(SandboxOrchestrator.class);
		when(sandbox.dispatchCodingTool(any(), any(), any()))
			.thenThrow(new io.metaloom.loom.agent.sandbox.error.SandboxQuotaException("concurrency cap reached"));

		TurnStreamer streamer = scripted(List.of(
			new TurnResult(null, null, List.of(new ToolCall("c1", "run_shell", new JsonObject().put("command", "echo hi")))),
			new TurnResult("Could not run the command.", null, List.of())));

		sandboxLoop(sandbox, streamer).run();

		AgentEvent toolEnd = firstEvent(AgentEventType.TOOL_END);
		assertTrue(toolEnd.data().getBoolean("isError"), "A sandbox failure must be flagged as an error result");
		assertTrue(toolEnd.data().getString("summary").startsWith("ERROR:"));
		// The loop still reached a final answer — the failure is a tool result, not a terminal error.
		assertNull(firstEvent(AgentEventType.ERROR));
		assertEquals("completed", firstEvent(AgentEventType.AGENT_END).data().getString("status"));
	}

}
