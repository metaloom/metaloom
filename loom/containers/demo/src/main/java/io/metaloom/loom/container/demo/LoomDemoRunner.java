package io.metaloom.loom.container.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.common.options.LoomOptionsLoader;

public class LoomDemoRunner {

	public static final Logger log = LoggerFactory.getLogger(LoomDemoRunner.class);

	public static void main(String[] args) {
		LoomOptionsLookup options = LoomOptionsLoader.createOrLoadOptions();
		Loom loom = Loom.create(options);
		try {
			loom.run();
		} catch (Throwable t) {
			log.error("Error while starting loom. Invoking shutdown.", t);
			loom.shutdownAndTerminate(10);
		}
	}
}
