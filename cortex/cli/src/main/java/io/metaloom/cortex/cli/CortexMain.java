package io.metaloom.cortex.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.CortexComponent;
import io.metaloom.cortex.cli.dagger.DaggerCortexComponent;

/**
 * Entry point of the Cortex worker.
 *
 * <p>
 * Cortex is a container, not a command line tool: there are no subcommands and no flags. The process assembles the Dagger graph, connects to Loom and
 * runs in the foreground until it is signalled to stop. Everything is configured through {@code cortex.yml} and the environment - see
 * {@code CortexEnvOptions} for the full variable list.
 * </p>
 */
public class CortexMain {

	private static final Logger log = LoggerFactory.getLogger(CortexMain.class);

	/** Cortex could not be configured - the process never went online. */
	public static final int EXIT_INVALID_CONFIGURATION = 2;

	/** Cortex went online but terminated with an error. */
	public static final int EXIT_ERROR = 10;

	public static void main(String... args) {
		if (args != null && args.length > 0) {
			// Older images invoked "cortex-cli.jar server start". Warn rather than fail so an
			// image with a stale CMD still starts.
			log.warn("Ignoring command line arguments {} - Cortex is configured through cortex.yml and the environment.", String.join(" ", args));
		}
		System.exit(run(null));
	}

	/**
	 * Run the worker in the foreground until it is shut down.
	 *
	 * @param options
	 *            explicit options, or {@code null} to resolve them from {@code cortex.yml} and the environment
	 * @return the process exit code
	 */
	public static int run(CortexOptions options) {
		CortexComponent component;
		try {
			component = buildComponent(options);
		} catch (RuntimeException e) {
			log.error("Cortex could not be configured.", e);
			return EXIT_INVALID_CONFIGURATION;
		}

		CortexOptions resolved = component.options();
		if (!hasNodeId(resolved)) {
			// A worker id is not optional: Loom keys registration, node-kind restrictions and run
			// attribution on it, and refuses a second worker announcing an id already in use. A
			// generated-per-process id would defeat both - it would create a fresh cortex_instance
			// on every restart and could collide with a live worker - so a missing id is a hard,
			// up-front failure rather than a silent fallback.
			log.error("No worker id configured. Set the CORTEX_NODE_ID environment variable (or nodeId in cortex.yml) "
				+ "before starting Cortex. The id must be unique per worker and stable across restarts: Loom keys "
				+ "registration, node-kind restrictions and run attribution on it, and rejects a second worker that "
				+ "announces an id already in use.");
			return EXIT_INVALID_CONFIGURATION;
		}

		Cortex cortex = component.cortex();
		try {
			// Blocks until the worker is shut down.
			cortex.run();
			return 0;
		} catch (Throwable t) {
			log.error("Error while running Cortex. Invoking shutdown.", t);
			cortex.shutdownAndTerminate(EXIT_ERROR);
			return EXIT_ERROR;
		}
	}

	/**
	 * Assemble the Dagger object graph for the worker.
	 *
	 * @param options
	 *            explicit options, or {@code null} to resolve them from {@code cortex.yml} and the environment
	 * @return the built component
	 */
	public static CortexComponent buildComponent(CortexOptions options) {
		return DaggerCortexComponent.builder()
			.options(options)
			.build();
	}

	private static boolean hasNodeId(CortexOptions options) {
		String nodeId = options.getNodeId();
		return nodeId != null && !nodeId.isBlank();
	}
}
