package io.metaloom.loom.agent.chat.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.ai.genai.llm.ChatMessage;
import io.metaloom.ai.genai.llm.ToolCall;
import io.metaloom.ai.genai.llm.ToolDefinition;
import io.vertx.core.json.JsonObject;

/**
 * Unit tests for the context budget (CHAT_TASKS CTX1).
 *
 * <p>
 * These assert the <em>properties</em> the eviction walk relies on — monotonicity, that nothing is free, that the reserve is honoured — rather than exact
 * token counts, which would only pin down the heuristic's arithmetic and would have to be rewritten the moment it is tuned.
 * </p>
 */
public class ContextBudgetTest {

	private static ContextBudget budget() {
		return new ContextBudget(16384, 2048, 1.0d);
	}

	@Test
	public void testLimitReserveAndRemaining() {
		ContextBudget budget = budget();
		assertEquals(16384, budget.limit());
		assertEquals(2048, budget.reserve());
		assertEquals(14336, budget.available(), "The prompt may use the window minus the completion reserve");
		assertEquals(14236, budget.remaining(100));
		assertEquals(0, budget.remaining(999_999), "Remaining is clamped at zero rather than going negative");
	}

	@Test
	public void testReserveIsClampedToTheWindow() {
		// A misconfigured reserve larger than the window must not produce a negative budget.
		ContextBudget budget = new ContextBudget(1000, 4000, 1.0d);
		assertEquals(0, budget.available());
	}

	@Test
	public void testEmptyInputsAreFree() {
		ContextBudget budget = budget();
		assertEquals(0, budget.estimate((String) null));
		assertEquals(0, budget.estimate(""));
		assertEquals(0, budget.estimate((List<ChatMessage>) null));
		assertEquals(0, budget.estimate(List.of()));
		assertEquals(0, budget.estimateTools(null));
		assertEquals(0, budget.estimateTools(List.of()));
	}

	@Test
	public void testTextEstimateFollowsTheCharsPerTokenHeuristic() {
		ContextBudget budget = budget();
		assertEquals(1, budget.estimate("abcd"));
		assertEquals(2, budget.estimate("abcde"), "A partial token still costs a whole one");
		assertEquals(25, budget.estimate("x".repeat(100)));
	}

	@Test
	public void testAMessageCostsMoreThanItsBareText() {
		ContextBudget budget = budget();
		String text = "Find every beach video shot in Vienna last summer";
		assertTrue(budget.estimate(ChatMessage.user(text)) > budget.estimate(text),
			"Every message carries a role marker and chat-template delimiters on top of its text");
	}

	@Test
	public void testToolCallsOnAMessageAreCharged() {
		ContextBudget budget = budget();
		ChatMessage bare = ChatMessage.assistantWithToolCalls(List.of());
		ChatMessage withCall = ChatMessage.assistantWithToolCalls(List.of(
			new ToolCall("c1", "find_assets", new JsonObject().put("query", "beach").put("limit", 50))));
		assertTrue(budget.estimate(withCall) > budget.estimate(bare),
			"A tool call's name and serialized arguments are prompt tokens like any other");
	}

	@Test
	public void testMultiMessageEstimateIsTheSumOfItsParts() {
		ContextBudget budget = budget();
		ChatMessage a = ChatMessage.user("first question");
		ChatMessage b = ChatMessage.assistant("first answer");
		assertEquals(budget.estimate(a) + budget.estimate(b), budget.estimate(List.of(a, b)));
	}

	@Test
	public void testEstimateIsMonotonic() {
		ContextBudget budget = budget();
		int previous = 0;
		List<ChatMessage> messages = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++) {
			messages.add(ChatMessage.user("message number " + i + " with some content"));
			int current = budget.estimate(messages);
			assertTrue(current > previous, "Adding a message must never lower the estimate");
			previous = current;
		}
	}

	@Test
	public void testToolSchemasAreCharged() {
		ContextBudget budget = budget();
		ToolDefinition tool = new ToolDefinition("find_assets", "Search for assets by filename, MIME type, tags or metadata",
			new JsonObject().put("type", "object").put("properties", new JsonObject().put("query", new JsonObject().put("type", "string"))));
		int one = budget.estimateTools(List.of(tool));
		assertTrue(one > 0, "A declared tool is not free — its schema is in the prompt on every turn");
		assertEquals(2 * one, budget.estimateTools(List.of(tool, tool)));
	}

	@Test
	public void testCalibrationScalesEveryEstimate() {
		String text = "x".repeat(400);
		int plain = new ContextBudget(16384, 2048, 1.0d).estimate(text);
		int doubled = new ContextBudget(16384, 2048, 2.0d).estimate(text);
		assertEquals(2 * plain, doubled);
	}

	@Test
	public void testCalibrationIsClampedOnConstruction() {
		assertEquals(ContextBudget.MAX_CALIBRATION, new ContextBudget(16384, 2048, 99d).calibration());
		assertEquals(ContextBudget.MIN_CALIBRATION, new ContextBudget(16384, 2048, 0.01d).calibration());
		assertEquals(1.0d, new ContextBudget(16384, 2048, 0d).calibration(), "A zero factor would make everything free");
		assertEquals(1.0d, new ContextBudget(16384, 2048, Double.NaN).calibration());
	}

	@Test
	public void testCalibrationFromAMeasuredTurn() {
		// The server charged 200 tokens for a prompt we estimated at 100 — the heuristic under-counts 2x.
		assertEquals(2.0d, ContextBudget.calibrationFrom(200, 100, 1.0d));
		assertEquals(0.5d, ContextBudget.calibrationFrom(50, 100, 1.0d));
	}

	@Test
	public void testCalibrationDoesNotCompoundAcrossRuns() {
		// Second run: the previous factor of 2.0 was already folded into the 200 token estimate, and the
		// server agrees at 200. The factor must stay at 2.0 rather than being multiplied up to 4.0.
		assertEquals(2.0d, ContextBudget.calibrationFrom(200, 200, 2.0d));
	}

	@Test
	public void testUnusableMeasurementsKeepThePreviousCalibration() {
		assertEquals(1.5d, ContextBudget.calibrationFrom(0, 100, 1.5d), "A server that reports nothing must change nothing");
		assertEquals(1.5d, ContextBudget.calibrationFrom(200, 0, 1.5d), "An empty prompt carries no information about the ratio");
	}
}
