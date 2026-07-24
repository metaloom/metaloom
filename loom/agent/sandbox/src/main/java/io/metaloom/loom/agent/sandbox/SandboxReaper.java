package io.metaloom.loom.agent.sandbox;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;

/**
 * Periodically terminates Session Runners past their idle-TTL or max-session (see
 * {@link SandboxOrchestrator#sweep()}). Started at boot from the bootstrap initializer and stopped on
 * shutdown. The blocking backend delete calls run off the event loop via {@code executeBlocking}.
 */
@Singleton
public class SandboxReaper {

	private static final Logger log = LoggerFactory.getLogger(SandboxReaper.class);

	/** How often the sweep runs. */
	public static final long SWEEP_INTERVAL_MS = 30_000L;

	private final Vertx vertx;
	private final SandboxOrchestrator orchestrator;

	private volatile long timerId = -1;

	@Inject
	public SandboxReaper(Vertx vertx, SandboxOrchestrator orchestrator) {
		this.vertx = vertx;
		this.orchestrator = orchestrator;
	}

	/** Start the periodic sweep. No-op when the sandbox is disabled or already running. */
	public synchronized void start() {
		if (!orchestrator.available()) {
			log.debug("Coding sandbox disabled — reaper not started");
			return;
		}
		if (timerId != -1) {
			return;
		}
		timerId = vertx.setPeriodic(SWEEP_INTERVAL_MS, id -> vertx.executeBlocking(() -> {
			try {
				orchestrator.sweep().forEach(t -> log.info("reaped session={} reason={}", t.session(), t.reason()));
			} catch (RuntimeException e) {
				log.warn("sandbox reaper sweep failed", e);
			}
			return null;
		}, false));
		log.info("Sandbox reaper started (interval={}ms)", SWEEP_INTERVAL_MS);
	}

	/** Stop the sweep and tear down all remaining runners. */
	public synchronized void stop() {
		if (timerId != -1) {
			vertx.cancelTimer(timerId);
			timerId = -1;
		}
		orchestrator.reapAll();
	}
}
