package io.metaloom.loom.core;

import java.util.concurrent.CountDownLatch;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.core.dagger.DaggerLoomCoreComponent;
import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.vertx.core.http.HttpServer;

public class LoomImpl implements Loom {

	private static final Logger log = LoggerFactory.getLogger(LoomImpl.class);

	private LoomOptionsLookup optionsLookup;

	private boolean shutdown = true;

	private CountDownLatch latch = new CountDownLatch(1);

	private LoomCoreComponent loomInternal;

	public LoomImpl(LoomOptionsLookup optionsLookup) {
		this.optionsLookup = optionsLookup;
	}

	@Override
	public Loom run() throws Exception {
		run(true);
		return this;
	}

	@Override
	public Loom run(boolean block) throws Exception {
		try {
			printProductInformation();
			log.info("Starting loom...");
			shutdown = false;
			loomInternal = DaggerLoomCoreComponent.builder().configuration(optionsLookup).build();
			loomInternal.boot().init(true);
		} catch (Exception e) {
			log.error("Error while starting loom", e);
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
		log.info("Loom shutting down...");
		try {
			loomInternal.boot().deinit();
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
	public void dontExit() throws InterruptedException {
		latch.await();
	}

	public Integer actualRestPort() {
		HttpServer server = getInternal().boot().getRestService().getServer();
		if (server == null) {
			return null;
		}
		return server.actualPort();
	}

	@Override
	public void shutdownAndTerminate(int code) {
		Runtime.getRuntime().exit(code);
	}

	public LoomCoreComponent getInternal() {
		return loomInternal;
	}

	private void printProductInformation() {
		log.info("###############################################################");
		logLines(SplashTextProvider.getSplashText());
		// log.info("#-------------------------------------------------------------#");
		// if (getOptions().getClusterOptions() != null && getOptions().getClusterOptions().isEnabled()) {
		// log.info(infoLine("Cluster Name: " + getOptions().getClusterOptions().getClusterName()));
		// }
		// log.info(infoLine("Loom Node Name: " + getOptions().getNodeName()));
		log.info("###############################################################");
	}

	private static void logLines(String text) {
		text.lines().forEachOrdered(line -> {
			log.info("# " + StringUtils.rightPad(line, 59) + " #");
		});
	}

}
