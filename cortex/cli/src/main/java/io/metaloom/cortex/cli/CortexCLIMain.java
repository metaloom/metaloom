package io.metaloom.cortex.cli;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import io.metaloom.cortex.cli.dagger.CortexComponent;
import io.metaloom.cortex.cli.dagger.DaggerCortexComponent;
import picocli.CommandLine;

public class CortexCLIMain {

	public static void main(String... args) {
		System.exit(execute(loadOptionsFromEnv(), args));
	}

	public static int execute(CortexOptions options, String... args) {
		CortexComponent.Builder builder = DaggerCortexComponent.builder();
		builder.options(options);
		CortexComponent cortexComponent = builder.build();
		CommandLine cli = cortexComponent.cli();
		return cli.execute(args);
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
