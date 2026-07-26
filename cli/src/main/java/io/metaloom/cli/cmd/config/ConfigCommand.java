package io.metaloom.cli.cmd.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.config.CliConfigFile;
import io.metaloom.cli.config.CliConfigLoader;
import io.metaloom.cli.config.Profile;
import io.metaloom.cli.output.Table;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Read and write {@code cli.yml}.
 */
@Singleton
@Command(name = "config", description = "Manage the CLI configuration file.",
	subcommands = {
		ConfigCommand.ListCommand.class,
		ConfigCommand.GetCommand.class,
		ConfigCommand.SetCommand.class,
		ConfigCommand.UseProfileCommand.class,
		ConfigCommand.PathCommand.class
	})
public class ConfigCommand extends AbstractCliCommand {

	@Inject
	public ConfigCommand() {
	}

	@Override
	protected Integer execute() {
		return usage();
	}

	/** The keys {@code config set} understands. */
	private static final java.util.Set<String> KEYS = java.util.Set.of("server", "output", "timeout");

	static Map<String, String> asMap(Profile profile) {
		Map<String, String> values = new LinkedHashMap<>();
		if (profile != null) {
			if (profile.getServer() != null) {
				values.put("server", profile.getServer());
			}
			if (profile.getOutput() != null) {
				values.put("output", profile.getOutput());
			}
			if (profile.getTimeout() != null) {
				values.put("timeout", profile.getTimeout());
			}
		}
		return values;
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "Show the active profile's settings.")
	public static class ListCommand extends AbstractCliCommand {

		@Inject
		CliConfigLoader loader;

		@Inject
		public ListCommand() {
		}

		@Override
		protected Integer execute() {
			Path file = loader.configFile(context);
			CliConfigFile config = loader.read(file);
			Map<String, String> values = asMap(config.find(context.profileName()));
			values.put("profile", context.profileName());
			// The effective server matters more than the stored one: it is what the next
			// command will actually talk to, after env and flags have had their say.
			values.put("effectiveServer", context.serverUri().toString());

			printer().printOne(values, map -> {
				Table table = new Table("KEY", "VALUE");
				map.forEach(table::row);
				return table;
			}, map -> map.get("effectiveServer"));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "get", description = "Print one setting.")
	public static class GetCommand extends AbstractCliCommand {

		@Parameters(index = "0", paramLabel = "KEY", description = "server, output or timeout.")
		String key;

		@Inject
		CliConfigLoader loader;

		@Inject
		public GetCommand() {
		}

		@Override
		protected Integer execute() {
			CliConfigFile config = loader.read(loader.configFile(context));
			String value = asMap(config.find(context.profileName())).get(key);
			if (value == null) {
				throw new CliException(ExitCode.NOT_FOUND,
					"'" + key + "' is not set in profile '" + context.profileName() + "'.");
			}
			printer().out().println(value);
			printer().out().flush();
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "set", description = "Set one setting on the active profile.")
	public static class SetCommand extends AbstractCliCommand {

		@Parameters(index = "0", paramLabel = "KEY", description = "server, output or timeout.")
		String key;

		@Parameters(index = "1", paramLabel = "VALUE")
		String value;

		@Inject
		CliConfigLoader loader;

		@Inject
		public SetCommand() {
		}

		@Override
		protected Integer execute() {
			if (!KEYS.contains(key)) {
				throw new CliException(ExitCode.USAGE,
					"Unknown setting '" + key + "'. Known settings: " + String.join(", ", KEYS) + ".");
			}
			// Validate before writing, so a typo fails now rather than on the next command.
			switch (key) {
				case "output" -> io.metaloom.cli.output.OutputFormat.parse(value);
				case "timeout" -> CliConfigLoader.parseDuration(value);
				case "server" -> io.metaloom.cli.config.ServerUrl.parse(value);
				default -> {
					// unreachable
				}
			}

			Path file = loader.configFile(context);
			CliConfigFile config = loader.read(file);
			Profile profile = config.profile(context.profileName());
			switch (key) {
				case "server" -> profile.setServer(value);
				case "output" -> profile.setOutput(value);
				case "timeout" -> profile.setTimeout(value);
				default -> {
					// unreachable
				}
			}
			loader.write(file, config);
			printer().printMessage("Set " + key + " = " + value + " in profile '" + context.profileName() + "'.");
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "use-profile", description = "Make a profile the default for future commands.")
	public static class UseProfileCommand extends AbstractCliCommand {

		@Parameters(index = "0", paramLabel = "NAME")
		String name;

		@Inject
		CliConfigLoader loader;

		@Inject
		public UseProfileCommand() {
		}

		@Override
		protected Integer execute() {
			Path file = loader.configFile(context);
			CliConfigFile config = loader.read(file);
			// Create it if it does not exist: `use-profile prod` followed by `config set
			// server ...` is the natural way to add one.
			config.profile(name);
			config.setCurrentProfile(name);
			loader.write(file, config);
			printer().printMessage("Now using profile '" + name + "'.");
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "path", description = "Print the path of the configuration file.")
	public static class PathCommand extends AbstractCliCommand {

		@Inject
		CliConfigLoader loader;

		@Inject
		public PathCommand() {
		}

		@Override
		protected Integer execute() {
			printer().out().println(loader.configFile(context));
			printer().out().flush();
			return ExitCode.OK;
		}
	}
}
