package io.metaloom.cli;

import java.nio.file.Path;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.config.CliConfigLoader;
import io.metaloom.cli.output.ColorMode;
import io.metaloom.cli.output.OutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * The {@code metaloom} root command.
 *
 * <p>Global options are declared as setters with {@link ScopeType#INHERIT}, matching the
 * convention in {@code CortexCLI}, and they write straight into {@link CliContext}. Because
 * they inherit, parsing any subcommand populates the context - there is no need to repeat
 * them per command or thread them through a mixin.</p>
 */
@Singleton
@Command(
	name = "metaloom",
	mixinStandardHelpOptions = true,
	versionProvider = MetaLoomCLI.VersionProvider.class,
	description = "Command line client for a MetaLoom (Loom) server.",
	synopsisSubcommandLabel = "COMMAND",
	showDefaultValues = true,
	subcommands = {
		io.metaloom.cli.cmd.auth.LoginCommand.class,
		io.metaloom.cli.cmd.auth.LogoutCommand.class,
		io.metaloom.cli.cmd.auth.WhoamiCommand.class,
		io.metaloom.cli.cmd.config.ConfigCommand.class,
		io.metaloom.cli.cmd.pipeline.PipelineCommand.class,
		io.metaloom.cli.cmd.run.RunCommand.class,
		io.metaloom.cli.cmd.infra.HealthCommand.class,
		io.metaloom.cli.cmd.infra.VersionCommand.class
	})
public class MetaLoomCLI implements Runnable {

	@Spec
	CommandSpec spec;

	private final CliContext context;

	@Inject
	public MetaLoomCLI(CliContext context) {
		this.context = context;
	}

	public CliContext context() {
		return context;
	}

	@Option(names = { "-s", "--server" }, scope = ScopeType.INHERIT, paramLabel = "URL",
		description = "Loom server URL. Env: METALOOM_SERVER, or LOOM_HOST + LOOM_PORT. Default: "
			+ CliContext.DEFAULT_SERVER)
	public void setServer(String server) {
		context.setServerUrl(server);
	}

	@Option(names = { "-P", "--profile" }, scope = ScopeType.INHERIT, paramLabel = "NAME",
		description = "Configuration profile to use. Env: METALOOM_PROFILE.")
	public void setProfile(String profile) {
		context.setProfile(profile);
	}

	@Option(names = "--token", scope = ScopeType.INHERIT, paramLabel = "TOKEN",
		description = "Bearer token. Env: METALOOM_TOKEN. Defaults to the stored credentials.")
	public void setToken(String token) {
		context.setToken(token);
	}

	@Option(names = "--token-file", scope = ScopeType.INHERIT, paramLabel = "FILE",
		description = "Read the bearer token from this file.")
	public void setTokenFile(Path tokenFile) {
		context.setTokenFile(tokenFile);
	}

	@Option(names = { "-o", "--output" }, scope = ScopeType.INHERIT, paramLabel = "FORMAT",
		description = "Output format: table, json or yaml. Env: METALOOM_OUTPUT.")
	public void setOutput(String output) {
		context.setOutput(OutputFormat.parse(output));
	}

	@Option(names = { "-q", "--quiet" }, scope = ScopeType.INHERIT,
		description = "Print only identifiers, one per line. Suppresses progress messages.")
	public void setQuiet(boolean quiet) {
		context.setQuiet(quiet);
	}

	@Option(names = "-v", scope = ScopeType.INHERIT,
		description = "Increase verbosity. Repeat for more (-v, -vv).")
	public void setVerbosity(boolean[] verbosity) {
		context.setVerbosity(verbosity == null ? 0 : verbosity.length);
	}

	@Option(names = "--color", scope = ScopeType.INHERIT, paramLabel = "WHEN",
		description = "Colourise output: auto, always or never. Honours NO_COLOR.")
	public void setColor(String color) {
		context.setColorMode(ColorMode.parse(color));
	}

	@Option(names = "--no-color", scope = ScopeType.INHERIT, description = "Disable coloured output.")
	public void setNoColor(boolean noColor) {
		if (noColor) {
			context.setColorMode(ColorMode.NEVER);
		}
	}

	@Option(names = "--timeout", scope = ScopeType.INHERIT, paramLabel = "DURATION",
		description = "Request timeout, e.g. 30s or 2m. Env: METALOOM_TIMEOUT.")
	public void setTimeout(String timeout) {
		context.setTimeout(CliConfigLoader.parseDuration(timeout));
	}

	@Option(names = "--config", scope = ScopeType.INHERIT, paramLabel = "FILE",
		description = "Configuration file. Env: METALOOM_CONFIG. Default: ~/.config/metaloom/cli.yml")
	public void setConfigFile(Path configFile) {
		context.setConfigFile(configFile);
	}

	@Option(names = { "-k", "--insecure" }, scope = ScopeType.INHERIT,
		description = "Do not verify the server's TLS certificate.")
	public void setInsecure(boolean insecure) {
		context.setInsecure(insecure);
	}

	@Override
	public void run() {
		// No subcommand: show the usage rather than doing something surprising.
		spec.commandLine().usage(spec.commandLine().getOut());
	}

	/** Reads the version from the filtered resource; a native image has no jar manifest. */
	public static class VersionProvider implements picocli.CommandLine.IVersionProvider {

		@Override
		public String[] getVersion() {
			return new String[] { "metaloom " + CliVersion.version() };
		}
	}
}
