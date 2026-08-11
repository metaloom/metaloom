package io.metaloom.loom.rest.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expires workers that stopped heartbeating.
 *
 * <p>A worker announces itself once and is then presumed present until its socket closes.
 * That presumption only holds for a socket that closes: a silent host, a network partition
 * or a frozen JVM leaves a half-open connection, whose close handler never fires. The
 * worker stays {@code ONLINE}, keeps winning placement decisions, and every task sent to it
 * is recovered only after its lease lapses — and then handed straight back to it. This is
 * the timer that turns a heartbeat that stopped arriving into a departure.</p>
 *
 * <h2>Two independent recoveries, not one</h2>
 *
 * <p>{@link LeaseReaper} rescues the <em>tasks</em>; this rescues the <em>fleet</em>. Running
 * only the former leaves the dead worker eligible for the very work it just lost, which is
 * why the eviction also drives an immediate {@link LeaseReaper#reclaimWorker} rather than
 * leaving the departed worker's leases to time out one by one.</p>
 *
 * @see ProcessorRegistry#expireStale(Instant, Duration, int)
 */
@Singleton
public class ProcessorPresenceReaper {

	private static final Logger log = LoggerFactory.getLogger(ProcessorPresenceReaper.class);

	/**
	 * How often a worker is expected to be heard from. Matches Cortex's own heartbeat
	 * cadence ({@code LoomControlChannel}, 10 s); the sweep also runs at this interval, so
	 * detection costs at most one extra beat beyond the deadline.
	 */
	public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000;

	/**
	 * How many beats may be missed before a worker is presumed gone. Six (a minute of
	 * silence) is deliberately generous: eviction reclaims the worker's in-flight tasks, so
	 * expiring a worker that was only briefly unreachable costs duplicated work. A worker
	 * that comes back simply re-registers.
	 */
	public static final int DEFAULT_MISSED_HEARTBEATS = 6;

	/** Upper bound on one sweep, so the reaper cannot itself become an outage. */
	public static final int DEFAULT_SWEEP_LIMIT = 100;

	/** Upper bound on the tasks reclaimed for a single evicted worker. */
	public static final int DEFAULT_RECLAIM_LIMIT = 500;

	public static final String PROP_HEARTBEAT_INTERVAL_MS = "loom.processor.heartbeatIntervalMs";
	public static final String ENV_HEARTBEAT_INTERVAL_MS = "LOOM_PROCESSOR_HEARTBEAT_INTERVAL_MS";
	public static final String PROP_MISSED_HEARTBEATS = "loom.processor.missedHeartbeats";
	public static final String ENV_MISSED_HEARTBEATS = "LOOM_PROCESSOR_MISSED_HEARTBEATS";
	public static final String PROP_EXPIRY_ENABLED = "loom.processor.expiryEnabled";
	public static final String ENV_EXPIRY_ENABLED = "LOOM_PROCESSOR_EXPIRY_ENABLED";

	private final ProcessorRegistry registry;

	/**
	 * Where an evicted worker's in-flight tasks are handed back. May be null in unit tests
	 * that exercise eviction alone, without a task store behind it.
	 */
	private final LeaseReaper leaseReaper;

	private final boolean enabled;
	private final long intervalMs;
	private final Duration maxAge;

	private ScheduledExecutorService scheduler;

	@Inject
	public ProcessorPresenceReaper(ProcessorRegistry registry, LeaseReaper leaseReaper) {
		this(registry, leaseReaper, resolveBoolean(PROP_EXPIRY_ENABLED, ENV_EXPIRY_ENABLED, true),
			resolveLong(PROP_HEARTBEAT_INTERVAL_MS, ENV_HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_INTERVAL_MS),
			(int) resolveLong(PROP_MISSED_HEARTBEATS, ENV_MISSED_HEARTBEATS, DEFAULT_MISSED_HEARTBEATS));
	}

	/**
	 * Construct a reaper with an explicit tolerance, bypassing the environment. Used by tests,
	 * which cannot wait a real minute to observe an expiry.
	 */
	public ProcessorPresenceReaper(ProcessorRegistry registry, LeaseReaper leaseReaper, boolean enabled,
		long heartbeatIntervalMs, int missedHeartbeats) {
		this.registry = registry;
		this.leaseReaper = leaseReaper;
		this.enabled = enabled;
		this.intervalMs = Math.max(1, heartbeatIntervalMs);
		this.maxAge = Duration.ofMillis(this.intervalMs * Math.max(1, missedHeartbeats));
	}

	/**
	 * Begin sweeping on a background thread. A no-op when expiry is switched off.
	 */
	public synchronized void start() {
		if (!enabled) {
			// The off switch exists for local development: a worker attached to a debugger
			// stops heartbeating for as long as it sits on a breakpoint, and being evicted
			// mid-session is the opposite of useful.
			log.info("Processor presence expiry is disabled ({}=false)", ENV_EXPIRY_ENABLED);
			return;
		}
		if (scheduler != null) {
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "processor-presence-reaper");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(this::sweepQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
		log.info("Processor presence reaper started, sweeping every {}ms; a worker silent for {} is evicted",
			intervalMs, maxAge);
	}

	public synchronized void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
	}

	/**
	 * Sweep without ever throwing.
	 *
	 * <p>{@code scheduleWithFixedDelay} cancels the schedule permanently if the task throws,
	 * so an exception escaping here would silently stop all presence expiry for the life of
	 * the process — the failure mode this class exists to prevent.</p>
	 */
	private void sweepQuietly() {
		try {
			sweep();
		} catch (Exception e) {
			log.error("Presence sweep failed; the reaper will try again next interval", e);
		}
	}

	/**
	 * Evict every worker that has gone silent, and hand its work back.
	 *
	 * @return how many workers were evicted
	 */
	public int sweep() {
		return sweep(Instant.now(), DEFAULT_SWEEP_LIMIT);
	}

	/**
	 * @param now   the moment the sweep is made
	 * @param limit maximum workers to evict in this sweep
	 * @return how many workers were evicted
	 */
	public int sweep(Instant now, int limit) {
		List<String> evicted = registry.expireStale(now, maxAge, limit);
		for (String nodeId : evicted) {
			log.warn("Worker '{}' has not been heard from for over {} - evicted and its work reclaimed", nodeId, maxAge);
			reclaim(nodeId);
		}
		return evicted.size();
	}

	/**
	 * Re-place the evicted worker's in-flight tasks.
	 *
	 * <p>Guarded: the worker is already out of the fleet by the time this runs, and failing
	 * to move its tasks must not undo that or stop the rest of the sweep. Anything missed
	 * here is still picked up by {@link LeaseReaper} once the lease lapses.</p>
	 */
	private void reclaim(String nodeId) {
		if (leaseReaper == null) {
			return;
		}
		try {
			leaseReaper.reclaimWorker(nodeId, DEFAULT_RECLAIM_LIMIT);
		} catch (Exception e) {
			log.error("Could not reclaim work held by evicted worker '{}'; its leases will lapse instead", nodeId, e);
		}
	}

	/** How long silence is tolerated before a worker is evicted. */
	public Duration getMaxAge() {
		return maxAge;
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Resolve a setting the way {@link WebSocketAuthenticator} does: JVM property first, then
	 * environment, then the default. These deliberately sit outside the option tree — they
	 * are operational tuning for one timer, not part of the validated configuration.
	 */
	private static String resolve(String property, String env) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			value = System.getenv(env);
		}
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static boolean resolveBoolean(String property, String env, boolean fallback) {
		String value = resolve(property, env);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	private static long resolveLong(String property, String env, long fallback) {
		String value = resolve(property, env);
		if (value == null) {
			return fallback;
		}
		try {
			long parsed = Long.parseLong(value);
			// A non-positive tolerance would evict the whole fleet on the first sweep.
			return parsed > 0 ? parsed : fallback;
		} catch (NumberFormatException e) {
			log.warn("Ignoring unparseable {}='{}'; using {}", env, value, fallback);
			return fallback;
		}
	}

}
