package io.metaloom.loom.agent.chat.loop;

import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.json.JsonObject;

/**
 * What one agent run is allowed to spend (CHAT_TASKS LP5).
 *
 * <h2>Scope</h2>
 *
 * <p>
 * This carries the <b>LLM call</b> ceiling only, which is what LP4's fan-out needs to be safe:
 * {@code map_over} multiplies LLM calls by its item count, so without a run-wide ceiling a single
 * tool call can cost 25 completions and a loop of them can cost hundreds. {@code LOOM_AI_MAX_TURNS}
 * bounds parent turns and says nothing about children.
 * </p>
 *
 * <p>
 * LP5 proper also wants ceilings on tool calls, dispatched node tasks and wall clock. Those are
 * deliberately <em>not</em> here yet: the node-task count lives behind the MCP boundary and needs
 * plumbing that LP4 does not, and adding half-wired counters would make LP5 look done when it is
 * not. The shape below is the one those ceilings should follow — a counter, a ceiling, and a
 * {@code try*} method whose {@code false} becomes an error tool result.
 * </p>
 *
 * <h2>Exhaustion is a result, not a crash</h2>
 *
 * <p>
 * Modelled on {@code AgentLoop.memoryWriteBudgetExhausted}: running out of budget returns an ERROR
 * tool result telling the model to stop and answer with what it has. It never aborts the run. A
 * bounded agent that reports its limit is more useful than one that dies, and the model can usually
 * still produce a decent answer from what it already gathered.
 * </p>
 *
 * <p>
 * Thread-safe: fan-out children consume from this concurrently.
 * </p>
 */
public class RunBudget {

	private final int maxLlmCalls;

	private final AtomicInteger llmCalls = new AtomicInteger();

	/**
	 * @param maxLlmCalls
	 *            {@code LOOM_AI_MAX_LLM_CALLS_PER_RUN}. A value {@code <= 0} disables the ceiling — an operator escape hatch, not the default.
	 */
	public RunBudget(int maxLlmCalls) {
		this.maxLlmCalls = maxLlmCalls;
	}

	/**
	 * Claim one LLM call.
	 *
	 * @return {@code true} when the call may proceed; {@code false} when the ceiling is reached. A refused claim is <em>not</em> counted, so the tally stays
	 *         an honest record of what was actually spent.
	 */
	public boolean tryLlmCall() {
		if (maxLlmCalls <= 0) {
			llmCalls.incrementAndGet();
			return true;
		}
		// Compare-and-set rather than incrementAndGet-then-check: concurrent fan-out children must not
		// be able to push the tally past the ceiling and report a spend that never happened.
		while (true) {
			int current = llmCalls.get();
			if (current >= maxLlmCalls) {
				return false;
			}
			if (llmCalls.compareAndSet(current, current + 1)) {
				return true;
			}
		}
	}

	/**
	 * How many LLM calls this run has actually made.
	 */
	public int llmCalls() {
		return llmCalls.get();
	}

	public int maxLlmCalls() {
		return maxLlmCalls;
	}

	/**
	 * Whether the LLM call ceiling is reached. Only ever a hint — {@link #tryLlmCall()} is the authority, since a concurrent fan-out can exhaust the budget
	 * between this check and the claim.
	 */
	public boolean isLlmBudgetExhausted() {
		return maxLlmCalls > 0 && llmCalls.get() >= maxLlmCalls;
	}

	/**
	 * The message handed to the model when it asks for an LLM call it cannot have.
	 */
	public String exhaustedMessage() {
		return "ERROR: This run has reached its limit of " + maxLlmCalls
			+ " LLM calls. Stop fanning out and answer with what you have.";
	}

	/**
	 * The tallies recorded in {@code chat.meta.lastRun}.
	 */
	public JsonObject toJson() {
		return new JsonObject()
			.put("llmCalls", llmCalls.get())
			.put("maxLlmCalls", maxLlmCalls);
	}

	@Override
	public String toString() {
		return "RunBudget[llmCalls=" + llmCalls.get() + "/" + maxLlmCalls + "]";
	}
}
