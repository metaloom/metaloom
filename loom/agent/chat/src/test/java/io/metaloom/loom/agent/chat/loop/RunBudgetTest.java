package io.metaloom.loom.agent.chat.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-run spend guard (CHAT_TASKS LP5, as far as LP4 needs it).
 */
public class RunBudgetTest {

	@Test
	public void testClaimsUpToTheCeiling() {
		RunBudget budget = new RunBudget(3);
		assertTrue(budget.tryLlmCall());
		assertTrue(budget.tryLlmCall());
		assertTrue(budget.tryLlmCall());
		assertFalse(budget.tryLlmCall(), "The fourth claim must be refused");
		assertEquals(3, budget.llmCalls(), "A refused claim is not counted — the tally records what was spent, not what was asked for");
		assertTrue(budget.isLlmBudgetExhausted());
	}

	@Test
	public void testZeroCeilingDisablesTheGuard() {
		RunBudget budget = new RunBudget(0);
		for (int i = 0; i < 500; i++) {
			assertTrue(budget.tryLlmCall());
		}
		assertFalse(budget.isLlmBudgetExhausted(), "A disabled ceiling is never exhausted");
		assertEquals(500, budget.llmCalls(), "…but the spend is still counted, so lastRun stays honest");
	}

	@Test
	public void testExhaustedMessageNamesTheCeiling() {
		assertTrue(new RunBudget(7).exhaustedMessage().contains("limit of 7 LLM calls"));
		assertTrue(new RunBudget(7).exhaustedMessage().startsWith("ERROR:"), "Exhaustion is an error tool result the model can react to");
	}

	@Test
	public void testTallyIsReported() {
		RunBudget budget = new RunBudget(9);
		budget.tryLlmCall();
		budget.tryLlmCall();
		assertEquals(2, budget.toJson().getInteger("llmCalls"));
		assertEquals(9, budget.toJson().getInteger("maxLlmCalls"));
	}

	/**
	 * The invariant that matters for fan-out: children claim concurrently, and the ceiling must hold exactly.
	 *
	 * <p>
	 * A naive {@code incrementAndGet() <= max} would let several threads past the line at once and report a spend that never happened. The barrier makes
	 * every thread claim at genuinely the same moment rather than hoping the scheduler interleaves them.
	 * </p>
	 */
	@Test
	public void testCeilingHoldsUnderConcurrentClaims() throws Exception {
		int threads = 16;
		int ceiling = 5;
		RunBudget budget = new RunBudget(ceiling);
		CyclicBarrier startTogether = new CyclicBarrier(threads);

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<Boolean>> claims = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				claims.add(() -> {
					startTogether.await(5, TimeUnit.SECONDS);
					return budget.tryLlmCall();
				});
			}
			List<Future<Boolean>> futures = pool.invokeAll(claims);
			int granted = 0;
			for (Future<Boolean> future : futures) {
				if (future.get()) {
					granted++;
				}
			}
			assertEquals(ceiling, granted, "Exactly as many claims may be granted as the ceiling allows");
			assertEquals(ceiling, budget.llmCalls(), "…and the tally must not overshoot it");
		} finally {
			pool.shutdownNow();
		}
	}
}
