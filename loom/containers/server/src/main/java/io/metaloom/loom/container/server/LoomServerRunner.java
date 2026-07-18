package io.metaloom.loom.container.server;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.error.ConfigurationValidationException;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.common.options.LoomOptionsLoader;

public class LoomServerRunner {

	public static final Logger log = LoggerFactory.getLogger(LoomServerRunner.class);

	public static final String VALIDATE_CONFIG_FLAG = "--validate-config";

	public static void main(String[] args) {
		if (hasFlag(args, VALIDATE_CONFIG_FLAG)) {
			System.exit(validateConfig());
		}

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
		} catch (ConfigurationValidationException e) {
			// The message already lists every problem - a stacktrace would only bury it.
			System.err.println("[loom] Invalid configuration. Loom will not start.");
			System.err.println(e.getMessage());
			System.exit(11);
		} catch (Throwable t) {
			System.err.println("[loom] Fatal error during bootstrap.");
			t.printStackTrace(System.err);
			System.exit(11);
		}
	}

	/**
	 * Load and validate the configuration without booting any service.
	 *
	 * @return the process exit code - 0 when the configuration is valid, 1 otherwise
	 */
	private static int validateConfig() {
		try {
			LoomOptionsLookup lookup = LoomOptionsLoader.createOrLoadOptions();
			File baseFolder = lookup.baseConfigFolder();
			System.out.println("[loom] Configuration is valid" + (baseFolder == null ? " (loaded from classpath)." : " (loaded from "
				+ baseFolder.getAbsolutePath() + ")."));
			return 0;
		} catch (ConfigurationValidationException e) {
			System.err.println(e.getMessage());
			return 1;
		} catch (Throwable t) {
			System.err.println("[loom] Could not load the configuration.");
			t.printStackTrace(System.err);
			return 1;
		}
	}

	private static boolean hasFlag(String[] args, String flag) {
		if (args == null) {
			return false;
		}
		for (String arg : args) {
			if (flag.equals(arg)) {
				return true;
			}
		}
		return false;
	}

}
