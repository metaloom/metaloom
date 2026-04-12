package io.metaloom.cortex.cli;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.CortexComponent;
import io.metaloom.cortex.cli.dagger.DaggerCortexComponent;
import picocli.CommandLine;

public class CortexCLIMain {

	public static void main(String... args) {
		System.exit(execute(args));
	}

	public static int execute(String... args) {
		// Pre-parse: resolve options from CLI args and environment variables
		CortexOptions options = parseOptions(args);

		CortexComponent.Builder builder = DaggerCortexComponent.builder();
		builder.options(options);
		CortexComponent cortexComponent = builder.build();
		CommandLine cli = cortexComponent.cli();
		cli.setDefaultValueProvider(new EnvDefaultProvider());
		return cli.execute(args);
	}

	/**
	 * Pre-parse CLI arguments with env var fallbacks to build {@link CortexOptions} before Dagger injection.
	 */
	private static CortexOptions parseOptions(String... args) {
		CortexCLI cli = new CortexCLI();
		CommandLine cmd = new CommandLine(cli);
		cmd.setDefaultValueProvider(new EnvDefaultProvider());
		try {
			cmd.parseArgs(args);
		} catch (Exception e) {
			// Ignore parse errors here; they will be reported during the real execution
		}
		return cli.toCortexOptions();
	}
}
