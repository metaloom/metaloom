package io.metaloom.cli;

import io.metaloom.cli.dagger.CliComponent;
import io.metaloom.cli.dagger.DaggerCliComponent;
import picocli.CommandLine;

/**
 * Entry point.
 *
 * <p>One parse, unlike {@code CortexCLIMain}: nothing in the Dagger graph needs the parsed
 * options at construction time, so the component can be built before parsing and the
 * configuration settled by {@code CliExecutionStrategy} in between parsing and executing.</p>
 */
public class MetaLoomCLIMain {

	public static void main(String[] args) {
		System.exit(execute(args));
	}

	/**
	 * Run the CLI and return the exit code without terminating the JVM.
	 *
	 * <p>Used by the tests and by the integration test, which drive the CLI in-process.</p>
	 */
	public static int execute(String... args) {
		CliComponent component = DaggerCliComponent.create();
		return component.cli().execute(args);
	}

	/**
	 * Run the CLI with the output redirected.
	 *
	 * @param out captures stdout
	 * @param err captures stderr
	 */
	public static int execute(java.io.PrintWriter out, java.io.PrintWriter err, String... args) {
		CliComponent component = DaggerCliComponent.create();
		CommandLine cmd = component.cli();
		cmd.setOut(out);
		cmd.setErr(err);
		return cmd.execute(args);
	}
}
