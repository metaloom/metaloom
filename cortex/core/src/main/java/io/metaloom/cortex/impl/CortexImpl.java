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

	private boolean shutdown = true;
	private CountDownLatch latch = new CountDownLatch(1);

	@Inject
	public CortexImpl(CortexOptions options, Lazy<Set<CortexNode<?, ?>>> nodes, CortexBootstrapInitializer boot) {
		this.options = options;
		this.nodes = nodes;
		this.boot = boot;
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
	public void shutdown() {
		if (shutdown) {
			log.info("Instance is already shut down...");
			return;
		}
		log.info("Cortex shutting down...");
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

}
