package io.metaloom.loom.container.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.common.options.LoomOptionsLoader;

public class LoomServerRunner {

	public static final Logger log = LoggerFactory.getLogger(LoomServerRunner.class);

	public static void main(String[] args) {
		LoomOptionsLookup optionsLookup = LoomOptionsLoader.createOrLoadOptions();
		Loom loom = Loom.create(optionsLookup);
		try {
			loom.run();
		} catch (Throwable t) {
			log.error("Error while starting loom. Invoking shutdown.", t);
			loom.shutdownAndTerminate(10);
		}
	}

}
