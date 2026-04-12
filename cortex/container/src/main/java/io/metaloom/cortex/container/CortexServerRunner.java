package io.metaloom.cortex.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.container.dagger.CortexServerComponent;
import io.metaloom.cortex.container.dagger.DaggerCortexServerComponent;

public class CortexServerRunner {

	public static final Logger log = LoggerFactory.getLogger(CortexServerRunner.class);

	public static void main(String[] args) {
		CortexOptions options = loadOptionsFromEnv();

		CortexServerComponent component = DaggerCortexServerComponent.builder()
			.options(options)
			.build();

		Cortex cortex = component.cortex();
		try {
			cortex.run();
		} catch (Throwable t) {
			log.error("Error while starting Cortex. Invoking shutdown.", t);
			cortex.shutdownAndTerminate(10);
		}
	}

	private static CortexOptions loadOptionsFromEnv() {
		CortexOptions options = new CortexOptions();

		String loomHost = System.getenv("LOOM_HOST");
		String loomPort = System.getenv("LOOM_PORT");
		String monitoringPort = System.getenv("CORTEX_MONITORING_PORT");

		LoomClientOptions loom = new LoomClientOptions();
		if (loomHost != null) {
			loom.setHostname(loomHost);
		}
		if (loomPort != null) {
			loom.setPort(Integer.parseInt(loomPort));
		}
		options.setLoom(loom);

		if (monitoringPort != null) {
			options.setMonitoringPort(Integer.parseInt(monitoringPort));
		}

		return options;
	}

}
