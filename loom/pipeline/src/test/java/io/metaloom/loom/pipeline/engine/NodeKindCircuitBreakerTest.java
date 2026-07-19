package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.engine.NodeKindCircuitBreaker.State;

/**
 * Refusing to keep dispatching a node kind that is broken everywhere.
 *
 * <p>Two opposing mistakes are being guarded against. Never opening means a missing
 * model file produces a hundred thousand identical failures that are, in the data,
 * indistinguishable from a hundred thousand bad files. Opening too eagerly stops a
 * healthy kind because the first few files happened to be corrupt.</p>
 */
public class NodeKindCircuitBreakerTest {

	private final AtomicLong now = new AtomicLong(1_000_000);

	private NodeKindCircuitBreaker breaker() {
		return new NodeKindCircuitBreaker(10, 0.9, 30_000, now::get);
	}

	private void record(NodeKindCircuitBreaker breaker, String kind, int successes, int failures) {
		for (int i = 0; i < successes; i++) {
			breaker.record(kind, true);
		}
		for (int i = 0; i < failures; i++) {
			breaker.record(kind, false);
		}
	}

	@Test
	void testAHealthyKindIsNeverParked() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "sha512", 100, 3);

		assertEquals(State.CLOSED, breaker.stateOf("sha512"));
		assertTrue(breaker.allowDispatch("sha512"));
	}

	@Test
	void testAFewEarlyFailuresDoNotTripIt() {
		NodeKindCircuitBreaker breaker = breaker();
		// All failures, but below the minimum sample. A breaker that tripped here would
		// stop a healthy kind because the first two files were corrupt.
		record(breaker, "whisper", 0, 5);

		assertEquals(State.CLOSED, breaker.stateOf("whisper"));
		assertTrue(breaker.allowDispatch("whisper"));
	}

	@Test
	void testSustainedFailureOpensIt() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);

		assertEquals(State.OPEN, breaker.stateOf("whisper"));
		assertFalse(breaker.allowDispatch("whisper"), "A kind failing everywhere must stop being dispatched");
	}

	@Test
	void testAMixedRateBelowTheThresholdStaysClosed() {
		NodeKindCircuitBreaker breaker = breaker();
		// 80% failure: bad, but plausibly the data rather than the environment.
		record(breaker, "ocr", 4, 16);

		assertEquals(State.CLOSED, breaker.stateOf("ocr"));
	}

	@Test
	void testOneKindOpeningDoesNotAffectAnother() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);
		record(breaker, "sha512", 10, 0);

		// The bulkhead property: a broken kind must not stop unrelated work.
		assertFalse(breaker.allowDispatch("whisper"));
		assertTrue(breaker.allowDispatch("sha512"));
	}

	@Test
	void testItStaysParkedUntilTheCooldownElapses() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);

		now.addAndGet(29_000);
		assertFalse(breaker.allowDispatch("whisper"));
		assertTrue(breaker.parkedForMs("whisper") > 0);

		now.addAndGet(2_000);
		assertTrue(breaker.allowDispatch("whisper"), "After the cooldown one probe must get through");
		assertEquals(State.HALF_OPEN, breaker.stateOf("whisper"));
	}

	@Test
	void testOnlyOneProbeIsAllowedThrough() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);
		now.addAndGet(31_000);

		assertTrue(breaker.allowDispatch("whisper"));
		// Letting the whole backlog through on recovery would knock a fragile kind
		// straight back over.
		assertFalse(breaker.allowDispatch("whisper"), "A second task must wait for the probe's verdict");
	}

	@Test
	void testASuccessfulProbeClosesTheCircuit() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);
		now.addAndGet(31_000);
		breaker.allowDispatch("whisper");

		breaker.record("whisper", true);

		assertEquals(State.CLOSED, breaker.stateOf("whisper"));
		assertTrue(breaker.allowDispatch("whisper"));
	}

	@Test
	void testTheOldFailuresDoNotImmediatelyReopenIt() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);
		now.addAndGet(31_000);
		breaker.allowDispatch("whisper");
		breaker.record("whisper", true);

		// Without resetting the counters on recovery, the very next failure would put
		// the ratio straight back over the threshold and the kind could never recover.
		breaker.record("whisper", false);
		assertEquals(State.CLOSED, breaker.stateOf("whisper"));
	}

	@Test
	void testAFailedProbeReopensIt() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);
		now.addAndGet(31_000);
		breaker.allowDispatch("whisper");

		breaker.record("whisper", false);

		assertEquals(State.OPEN, breaker.stateOf("whisper"));
		assertFalse(breaker.allowDispatch("whisper"), "The cooldown must start again, not probe repeatedly");
	}

	@Test
	void testResetClearsEverything() {
		NodeKindCircuitBreaker breaker = breaker();
		record(breaker, "whisper", 0, 10);
		assertEquals(State.OPEN, breaker.stateOf("whisper"));

		// After an operator has fixed the underlying problem, waiting out a cooldown
		// is pointless.
		breaker.reset();

		assertEquals(State.CLOSED, breaker.stateOf("whisper"));
		assertTrue(breaker.allowDispatch("whisper"));
	}

	@Test
	void testAnUnknownKindIsAllowed() {
		assertTrue(breaker().allowDispatch("never-seen"));
		assertEquals(State.CLOSED, breaker().stateOf("never-seen"));
	}

}
