package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * What the breaker tells an operator.
 *
 * <p>
 * The breaker is the one component that can silently stop a fleet doing work: a parked kind
 * produces no failures, no dispatches and no errors — a graph of throughput just goes flat. It
 * carried no instrumentation at all, so "why did whisper stop?" was answerable only by reading
 * logs on whichever Loom instance happened to trip it.
 * </p>
 *
 * <p>
 * The gauge is the state and the counter is the transitions, and both are needed: the gauge alone
 * cannot distinguish a kind that opened once this morning from one that has been flapping all day,
 * and the counter alone cannot say whether it is open <em>now</em>.
 * </p>
 */
public class NodeKindCircuitBreakerMetricsTest {

	private static final String STATE_GAUGE = "loom_node_circuit_breaker_state{kind=sha512}";

	private final AtomicLong now = new AtomicLong(1_000_000);

	private NodeKindCircuitBreaker breaker(RecordingLoomMetrics metrics) {
		return new NodeKindCircuitBreaker(10, 0.9, 30_000, now::get, metrics);
	}

	private void fail(NodeKindCircuitBreaker breaker, String kind, int times) {
		for (int i = 0; i < times; i++) {
			breaker.record(kind, false);
		}
	}

	@Test
	void testAKindsStateGaugeIsBoundTheFirstTimeItIsSeen() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		breaker.allowDispatch("sha512");

		// Bound lazily off the live kind set rather than from a fixed list, because the kinds are
		// whatever the installed nodes declare.
		assertTrue(metrics.gaugeNames().contains(STATE_GAUGE),
			"Seeing a kind must publish its state gauge. Bound: " + metrics.gaugeNames());
		assertEquals(0d, metrics.gauge(STATE_GAUGE), "A kind nothing has failed reads closed");
	}

	@Test
	void testTheGaugeIsPerKind() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		breaker.allowDispatch("sha512");
		breaker.allowDispatch("whisper");
		fail(breaker, "whisper", 10);

		// One broken kind must not make the healthy one look broken - a single untagged gauge
		// would do exactly that, and the whole point of the breaker is that it is keyed by kind.
		assertEquals(0d, metrics.gauge(STATE_GAUGE));
		assertEquals(2d, metrics.gauge("loom_node_circuit_breaker_state{kind=whisper}"));
	}

	@Test
	void testTrippingMovesTheGaugeAndTheCounter() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		fail(breaker, "sha512", 10);

		assertEquals(2d, metrics.gauge(STATE_GAUGE), "Open is the most severe value");
		assertEquals(1, metrics.trips("sha512"));
		assertFalse(breaker.allowDispatch("sha512"), "The gauge is describing a real park");
	}

	@Test
	void testTheGaugeReadsHalfOpenWhileProbing() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		fail(breaker, "sha512", 10);
		now.addAndGet(30_001);
		assertTrue(breaker.allowDispatch("sha512"), "Cooldown elapsed, so one probe goes through");

		// Between open and closed, and worth its own value: half-open is the state in which a
		// recovering kind is dispatching at one task at a time, which looks like a stall.
		assertEquals(1d, metrics.gauge(STATE_GAUGE));
	}

	@Test
	void testASuccessfulProbeReturnsTheGaugeToClosedWithoutCountingATrip() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		fail(breaker, "sha512", 10);
		now.addAndGet(30_001);
		breaker.allowDispatch("sha512");
		breaker.record("sha512", true);

		assertEquals(0d, metrics.gauge(STATE_GAUGE));
		assertEquals(1, metrics.trips("sha512"), "Recovering is not a trip");
	}

	@Test
	void testEachFailedProbeCountsAsAFurtherTrip() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		fail(breaker, "sha512", 10);
		for (int probe = 0; probe < 3; probe++) {
			now.addAndGet(30_001);
			breaker.allowDispatch("sha512");
			breaker.record("sha512", false);
		}

		// The gauge has read open the whole time. Only the counter shows that three separate
		// attempts to recover were made and every one of them failed.
		assertEquals(2d, metrics.gauge(STATE_GAUGE));
		assertEquals(4, metrics.trips("sha512"));
	}

	@Test
	void testAResetReturnsTheGaugeToClosed() {
		RecordingLoomMetrics metrics = new RecordingLoomMetrics();
		NodeKindCircuitBreaker breaker = breaker(metrics);

		fail(breaker, "sha512", 10);
		breaker.reset();

		// The gauge survives the reset and reports the truth: a kind with no stats is closed. A
		// gauge left reading open after an operator cleared the breaker would be worse than none.
		assertEquals(0d, metrics.gauge(STATE_GAUGE));
	}

	@Test
	void testABreakerWithoutAMetricsBackendStillWorks() {
		NodeKindCircuitBreaker breaker = new NodeKindCircuitBreaker(10, 0.9, 30_000, now::get, null);

		fail(breaker, "sha512", 10);

		assertFalse(breaker.allowDispatch("sha512"));
	}
}
