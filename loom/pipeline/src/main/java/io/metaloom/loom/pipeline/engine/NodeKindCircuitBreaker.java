package io.metaloom.loom.pipeline.engine;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.common.metrics.NoopLoomMetrics;

/**
 * Stops dispatching a node kind that is failing everywhere.
 *
 * <p>Without this, a kind that is broken for an environmental reason — a model file
 * missing from every worker, a GPU driver fault, an expired API key — burns the
 * fleet and fills the dead-letter queue with a hundred thousand identical failures
 * before anyone notices. The failures are also indistinguishable, in the data, from
 * a hundred thousand genuinely bad files.</p>
 *
 * <h2>Keyed by kind, not by worker</h2>
 *
 * <p>A worker that dies is already handled by leases. What leases cannot see is a
 * kind that fails <em>on every worker</em>, which is the case where retrying harder
 * makes things worse rather than better.</p>
 *
 * <h2>Opening is deliberately reluctant</h2>
 *
 * <p>A breaker that trips on early noise is worse than none: it would stop a
 * perfectly healthy kind because the first two files happened to be corrupt. It
 * therefore needs both a minimum sample and a sustained failure rate before it
 * opens.</p>
 *
 * <p>Thread-safe: the engine calls this under its monitor, but a shared instance is
 * consulted by several runs.</p>
 */
public class NodeKindCircuitBreaker {

	private static final Logger log = LoggerFactory.getLogger(NodeKindCircuitBreaker.class);

	/** Below this many observations a kind is never judged. */
	public static final int DEFAULT_MIN_SAMPLES = 20;

	/** Failure ratio at or above which the breaker opens. */
	public static final double DEFAULT_FAILURE_THRESHOLD = 0.9;

	/** How long a kind stays parked before a probe is allowed through. */
	public static final long DEFAULT_COOLDOWN_MS = 30_000;

	/** Meter name of the per-kind state gauge; see {@link #gaugeValueOf(State)} for the encoding. */
	static final String STATE_GAUGE = "loom_node_circuit_breaker_state";

	/** What a kind's breaker is currently doing. */
	public enum State {
		/** Dispatching normally. */
		CLOSED,
		/** Failing everywhere; dispatch is parked. */
		OPEN,
		/** Cooldown elapsed; one probe is allowed to test the water. */
		HALF_OPEN
	}

	/**
	 * The gauge encoding of a state.
	 *
	 * <p>Ordered by severity - 0 closed, 1 half-open, 2 open - deliberately <em>not</em>
	 * {@link Enum#ordinal()}, which declares OPEN before HALF_OPEN. A dashboard that alerts on
	 * {@code > 0} or graphs the max across kinds has to be able to read the number as "how bad",
	 * and reordering the enum for any other reason must not silently invert that.</p>
	 */
	private static int gaugeValueOf(State state) {
		return switch (state) {
			case CLOSED -> 0;
			case HALF_OPEN -> 1;
			case OPEN -> 2;
		};
	}

	private static final class KindStats {
		int successes;
		int failures;
		/**
		 * Volatile because the state gauge is read by the scrape thread, which deliberately does not
		 * take this breaker's monitor: a Prometheus scrape must never be able to block behind a run
		 * that is busy dispatching, and a reading that is a few microseconds old is worth far more
		 * than a scrape that stalls.
		 */
		volatile State state = State.CLOSED;
		long openedAt;
		boolean probeInFlight;
	}

	private final Map<String, KindStats> byKind = new ConcurrentHashMap<>();
	private final int minSamples;
	private final double failureThreshold;
	private final long cooldownMs;
	private final LongSupplier clock;
	private final LoomMetrics metrics;
	/** Kinds whose state gauge has been bound, so a hot path does not re-register on every call. */
	private final Set<String> gaugedKinds = new LinkedHashSet<>();

	public NodeKindCircuitBreaker() {
		this(NoopLoomMetrics.INSTANCE);
	}

	/**
	 * @param metrics where breaker trips and per-kind state are reported
	 */
	public NodeKindCircuitBreaker(LoomMetrics metrics) {
		this(DEFAULT_MIN_SAMPLES, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MS, System::currentTimeMillis, metrics);
	}

	/**
	 * @param minSamples       observations required before a kind can be judged
	 * @param failureThreshold ratio at or above which the breaker opens
	 * @param cooldownMs       how long to park before probing
	 * @param clock            time source; injected so tests need no sleeping
	 */
	public NodeKindCircuitBreaker(int minSamples, double failureThreshold, long cooldownMs, LongSupplier clock) {
		this(minSamples, failureThreshold, cooldownMs, clock, NoopLoomMetrics.INSTANCE);
	}

	/**
	 * @param minSamples       observations required before a kind can be judged
	 * @param failureThreshold ratio at or above which the breaker opens
	 * @param cooldownMs       how long to park before probing
	 * @param clock            time source; injected so tests need no sleeping
	 * @param metrics          where breaker trips and per-kind state are reported
	 */
	public NodeKindCircuitBreaker(int minSamples, double failureThreshold, long cooldownMs, LongSupplier clock,
		LoomMetrics metrics) {
		this.minSamples = minSamples;
		this.failureThreshold = failureThreshold;
		this.cooldownMs = cooldownMs;
		this.clock = clock;
		this.metrics = metrics == null ? NoopLoomMetrics.INSTANCE : metrics;
	}

	/**
	 * Find or create a kind's stats, publishing its state gauge the first time the kind is seen.
	 *
	 * <p>Bound lazily rather than from a fixed list because the set of kinds is whatever the
	 * installed nodes declare, and it is bounded by that - never by anything per-run.</p>
	 *
	 * <p>The gauge is registered <em>outside</em> {@code computeIfAbsent}: the mapping function of a
	 * {@link ConcurrentHashMap} runs while a bin is locked, and calling out to a metrics registry
	 * from inside it is how an innocuous-looking supplier turns into a deadlock.</p>
	 */
	private KindStats statsFor(String nodeKind) {
		KindStats stats = byKind.computeIfAbsent(nodeKind, k -> new KindStats());
		if (gaugedKinds.add(nodeKind)) {
			// Reads the volatile field rather than calling the synchronised stateOf(), so a scrape
			// cannot end up waiting on the monitor a dispatching run is holding.
			metrics.bindGauge(STATE_GAUGE, "kind", nodeKind, () -> {
				KindStats current = byKind.get(nodeKind);
				return gaugeValueOf(current == null ? State.CLOSED : current.state);
			});
		}
		return stats;
	}

	/**
	 * Park a kind and say so.
	 *
	 * <p>The single place the breaker opens, so the trip counter cannot drift from the state gauge:
	 * a trip is a closed-to-open or probe-failed transition, which is what an operator counts to
	 * distinguish one broken deployment from a kind that has been flapping all afternoon.</p>
	 */
	private void trip(KindStats stats, String nodeKind) {
		stats.state = State.OPEN;
		stats.openedAt = clock.getAsLong();
		metrics.recordCircuitBreakerTrip(nodeKind);
	}

	/**
	 * May a task of this kind be dispatched now?
	 *
	 * @param nodeKind the kind
	 * @return true to dispatch, false to park
	 */
	public synchronized boolean allowDispatch(String nodeKind) {
		KindStats stats = statsFor(nodeKind);
		if (stats.state == State.CLOSED) {
			return true;
		}
		if (stats.state == State.OPEN) {
			if (clock.getAsLong() - stats.openedAt < cooldownMs) {
				return false;
			}
			// Cooldown elapsed. Let exactly one task through to find out whether the
			// underlying problem is gone.
			stats.state = State.HALF_OPEN;
			stats.probeInFlight = true;
			log.info("Circuit for node kind '{}' is half-open; probing with one task", nodeKind);
			return true;
		}
		// HALF_OPEN: only the single probe goes through, or a recovering kind would be
		// hit by the entire backlog at once and knock itself straight back over.
		if (stats.probeInFlight) {
			return false;
		}
		stats.probeInFlight = true;
		return true;
	}

	/**
	 * Record how a task of this kind turned out.
	 *
	 * @param nodeKind  the kind
	 * @param succeeded true when it completed, false when it failed
	 */
	public synchronized void record(String nodeKind, boolean succeeded) {
		KindStats stats = statsFor(nodeKind);

		if (stats.state == State.HALF_OPEN) {
			stats.probeInFlight = false;
			if (succeeded) {
				// Recovered. Counters reset so the old failures cannot immediately
				// re-open the breaker on the next observation.
				stats.state = State.CLOSED;
				stats.successes = 0;
				stats.failures = 0;
				log.info("Circuit for node kind '{}' closed after a successful probe", nodeKind);
			} else {
				// Counted as a trip of its own: a kind that fails every probe for an hour is a
				// different situation from one that opened once, and only the counter tells them
				// apart - the gauge reads OPEN throughout either.
				trip(stats, nodeKind);
				log.warn("Probe for node kind '{}' failed; circuit stays open", nodeKind);
			}
			return;
		}

		if (succeeded) {
			stats.successes++;
		} else {
			stats.failures++;
		}

		int total = stats.successes + stats.failures;
		if (stats.state == State.CLOSED && total >= minSamples
			&& (double) stats.failures / total >= failureThreshold) {
			trip(stats, nodeKind);
			log.error("Node kind '{}' failed {} of {} tasks; parking dispatch for {}ms",
				nodeKind, stats.failures, total, cooldownMs);
		}
	}

	/**
	 * @param nodeKind the kind
	 * @return its current state
	 */
	public synchronized State stateOf(String nodeKind) {
		KindStats stats = byKind.get(nodeKind);
		return stats == null ? State.CLOSED : stats.state;
	}

	/** @return how long until this kind is probed again, or 0 when it is not parked */
	public synchronized long parkedForMs(String nodeKind) {
		KindStats stats = byKind.get(nodeKind);
		if (stats == null || stats.state != State.OPEN) {
			return 0;
		}
		return Math.max(0, cooldownMs - (clock.getAsLong() - stats.openedAt));
	}

	/**
	 * Forget everything, e.g. after an operator has fixed the underlying problem.
	 *
	 * <p>{@code gaugedKinds} is deliberately kept: the gauges themselves are still registered, and a
	 * kind with no stats reads CLOSED, which is exactly what a reset means. Clearing it would only
	 * make the next observation try to register a series that already exists.</p>
	 */
	public synchronized void reset() {
		byKind.clear();
	}

}
