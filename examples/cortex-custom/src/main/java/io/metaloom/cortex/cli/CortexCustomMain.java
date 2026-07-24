package io.metaloom.cortex.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.CortexComponent;
import io.metaloom.cortex.cli.dagger.DaggerCortexComponent;

/**
 * Entry point for the custom Cortex <b>daemon</b> example.
 *
 * <p>
 * Unlike the real Cortex CLI, this example has no command layer. It simply assembles a {@link Cortex} instance that includes the custom
 * {@code hello-world} node (from the {@code cortex-custom-node} module) and runs it in the foreground until the process is signalled to stop. The worker
 * connects to a Loom backend using the standard environment variables:
 * </p>
 *
 * <ul>
 * <li>{@code LOOM_HOST} / {@code LOOM_PORT} — the Loom backend to register with (defaults {@code localhost}:{@code 7733}).</li>
 * <li>{@code CORTEX_MONITORING_PORT} — health/readiness HTTP port (default {@code 8093}).</li>
 * <li>{@code CORTEX_META_PATH} — local sidecar metadata cache path.</li>
 * <li>{@code CORTEX_NODE_ID} — stable worker id.</li>
 * </ul>
 *
 * <p>
 * Options are resolved by the {@code CortexOptionsLoader} (environment + config) because {@code null} is passed to the component builder.
 * </p>
 */
public class CortexCustomMain {

	private static final Logger log = LoggerFactory.getLogger(CortexCustomMain.class);

	public static void main(String... args) {
		Cortex cortex = buildComponent(null).cortex();
		Runtime.getRuntime().addShutdownHook(new Thread(cortex::shutdown, "cortex-shutdown"));
		try {
			// Blocks until the daemon is shut down.
			cortex.run();
		} catch (Throwable t) {
			log.error("Error while running the custom Cortex daemon. Invoking shutdown.", t);
			cortex.shutdownAndTerminate(10);
		}
	}

	/**
	 * Assemble the Dagger object graph for the custom Cortex daemon.
	 *
	 * @param options
	 *            explicit options, or {@code null} to resolve them from the environment / config via the {@code CortexOptionsLoader}
	 * @return the built component
	 */
	public static CortexComponent buildComponent(CortexOptions options) {
		return DaggerCortexComponent.builder()
			.options(options)
			.build();
	}
}
