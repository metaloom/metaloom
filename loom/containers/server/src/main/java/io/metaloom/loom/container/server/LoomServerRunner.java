package io.metaloom.loom.container.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.common.options.LoomOptionsLoader;

public class LoomServerRunner {

	public static final Logger log = LoggerFactory.getLogger(LoomServerRunner.class);

	public static void main(String[] args) {
		System.err.println("[loom] starting native loom-server ...");
		try {
			LoomOptionsLookup optionsLookup = LoomOptionsLoader.createOrLoadOptions();
			Loom loom = Loom.create(optionsLookup);
			try {
				loom.run();
			} catch (Throwable t) {
				System.err.println("[loom] Error while starting loom. Invoking shutdown.");
				t.printStackTrace(System.err);
				log.error("Error while starting loom. Invoking shutdown.", t);
				loom.shutdownAndTerminate(10);
			}
		} catch (Throwable t) {
			System.err.println("[loom] Fatal error during bootstrap.");
			t.printStackTrace(System.err);
			System.exit(11);
		}
	}

}
