package io.metaloom.cortex.impl;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.Cortex;
import dagger.Lazy;
import io.metaloom.cortex.api.node.CortexNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.impl.boot.CortexBootstrapInitializer;
import io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector;

@Singleton
public class CortexImpl implements Cortex {

	private static final Logger log = LoggerFactory.getLogger(CortexImpl.class);

	private final CortexOptions options;
	/**
	 * Deferred on purpose.
	 *
	 * <p>Injecting the set directly builds every node the worker was compiled with -
	 * face detection loading its model pack, whisper, OCR and the rest - merely to
	 * start up. A worker that only hashes has no business initialising any of that,
	 * and on a machine without the models it cannot even start. Nothing outside
	 * {@link #checkNodes()} reads this.</p>
	 */
	private final Lazy<Set<CortexNode<?, ?>>> nodes;
	private final CortexBootstrapInitializer boot;
	private final LoomBulkSyncCollector syncCollector;

	private boolean shutdown = true;
	private CountDownLatch latch = new CountDownLatch(1);

	/**
	 * JVM shutdown hook that runs the graceful shutdown, so a {@code SIGTERM} flushes computed results and drains
	 * in-flight tasks instead of abandoning both. Registered in {@link #run(boolean)} and removed again by a
	 * {@link #shutdown()} that was called directly.
	 */
	private Thread shutdownHook;

	@Inject
	public CortexImpl(CortexOptions options, Lazy<Set<CortexNode<?, ?>>> nodes, CortexBootstrapInitializer boot,
		LoomBulkSyncCollector syncCollector) {
		this.options = options;
		this.nodes = nodes;
		this.boot = boot;
		this.syncCollector = syncCollector;
	}

	@Override
	public void checkNodes() {
		for (CortexNode<?, ?> node : nodes.get()) {
			System.out.println(node.options());
		}
	}

	@Override
	public Cortex run() throws Exception {
		return run(true);
	}

	@Override
	public Cortex run(boolean block) throws Exception {
		try {
			log.info("Starting Cortex...");
			shutdown = false;
			registerShutdownHook();
			boot.init(options.getMonitoringPort());
		} catch (Exception e) {
			log.error("Error while starting Cortex", e);
			throw e;
		}

		if (block) {
			dontExit();
		}
		return this;
	}

	@Override
	public synchronized void shutdown() {
		if (shutdown) {
			log.info("Instance is already shut down...");
			return;
		}
		log.info("Cortex shutting down...");
		// Flush pending sync results while the Loom client is still available, then drop the JVM hook (it is only a
		// safety net for an exit that does not come through here).
		flushSyncBuffer();
		unregisterShutdownHook();
		try {
			boot.deinit();
		} catch (Exception e) {
			log.error("Error while shutting down", e);
		}
		try {
			latch.countDown();
		} catch (Exception e) {
			log.debug("Error while releasing latch. Maybe it was already released.", e);
		}
		shutdown = true;
	}

	@Override
	public void shutdownAndTerminate(int code) {
		shutdown();
		Runtime.getRuntime().exit(code);
	}

	@Override
	public void dontExit() throws InterruptedException {
		latch.await();
	}

	@Override
	public Integer actualMonitoringPort() {
		return boot.actualMonitoringPort();
	}

	/**
	 * Register a JVM shutdown hook that runs the ordinary graceful shutdown.
	 *
	 * <p>{@code SIGTERM} is how a worker is normally stopped - {@code docker stop}, a Kubernetes
	 * eviction, a systemd restart - and it does not come through {@link #shutdown()} on its own. Without
	 * this hook such a stop would discard results computed but not yet pushed to Loom, and would abandon
	 * every task the worker held until its lease lapsed. Both are what {@link #shutdown()} exists to
	 * avoid, so the hook simply calls it.</p>
	 *
	 * <p>A graceful shutdown removes the hook again, so it fires at most once.</p>
	 */
	private synchronized void registerShutdownHook() {
		if (shutdownHook != null) {
			return;
		}
		shutdownHook = new Thread(this::shutdown, "cortex-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	private synchronized void unregisterShutdownHook() {
		if (shutdownHook == null) {
			return;
		}
		try {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		} catch (IllegalStateException e) {
			// The JVM is already shutting down and running hooks - which is the case when
			// this very hook is what called shutdown(). Nothing to remove.
			log.debug("Could not remove the shutdown hook, JVM is already shutting down.", e);
		}
		shutdownHook = null;
	}

	/**
	 * Flush any results still buffered in the bulk-sync collector to Loom. Safe to call multiple times - an empty buffer
	 * is a no-op - and never throws, so it is safe to run from a shutdown hook.
	 */
	private void flushSyncBuffer() {
		try {
			int flushed = syncCollector.flush();
			if (flushed > 0) {
				log.info("Flushed {} pending sync result(s) to Loom during shutdown.", flushed);
			}
		} catch (Exception e) {
			log.error("Error while flushing sync buffer during shutdown.", e);
		}
	}

}
