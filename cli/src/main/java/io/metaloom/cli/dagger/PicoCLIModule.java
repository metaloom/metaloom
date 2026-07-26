package io.metaloom.cli.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cli.CliContext;
import io.metaloom.cli.ExitCode;
import io.metaloom.cli.MetaLoomCLI;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.client.ClientErrors;
import io.metaloom.cli.cmd.org.ListCommands;
import picocli.AutoComplete;
import picocli.CommandLine;

/**
 * Builds the configured {@link CommandLine}.
 */
@Module
public abstract class PicoCLIModule {

	@Provides
	@Singleton
	static CommandLine commandLine(MetaLoomCLI root, DaggerCliFactory factory, CliExecutionStrategy strategy,
		CliContext context) {

		CommandLine cmd = new CommandLine(root, factory);
		cmd.setExecutionStrategy(strategy);
		cmd.setCaseInsensitiveEnumValuesAllowed(true);
		// Subcommands are declared with @Command(subcommands = ...) and built by the Dagger
		// factory. Only the command groups that cannot be annotated onto the root - because
		// they live in a shared holder class - are registered explicitly here.
		cmd.addSubcommand(ListCommands.SpaceGroup.class);
		cmd.addSubcommand(ListCommands.LibraryGroup.class);
		cmd.addSubcommand(ListCommands.PoolGroup.class);
		cmd.addSubcommand(ListCommands.UserGroup.class);
		cmd.addSubcommand(ListCommands.GroupGroup.class);
		cmd.addSubcommand(ListCommands.RoleGroup.class);
		cmd.addSubcommand(new CommandLine(new AutoComplete.GenerateCompletion(), factory).getCommandSpec()
			.name("completion"));

		// Give every subcommand -h/--help and -V/--version. Doing it here rather than
		// repeating mixinStandardHelpOptions on ~30 @Command annotations means a new command
		// cannot be added without it - `metaloom pipeline --help` failing is the kind of
		// thing nobody notices until a user hits it.
		addStandardHelpRecursively(cmd);

		cmd.setExecutionExceptionHandler((e, commandLine, parseResult) -> {
			CliException failure = ClientErrors.toCliException(e, "command failed");
			commandLine.getErr().println(commandLine.getColorScheme().errorText("error: " + failure.getMessage()));
			if (context.getVerbosity() > 0) {
				if (failure.getDetail() != null && !failure.getDetail().isBlank()) {
					commandLine.getErr().println(failure.getDetail());
				}
				failure.printStackTrace(commandLine.getErr());
			}
			commandLine.getErr().flush();
			return failure.getExitCode();
		});

		cmd.setParameterExceptionHandler((e, args) -> {
			CommandLine failing = e.getCommandLine();
			failing.getErr().println(failing.getColorScheme().errorText(e.getMessage()));
			failing.usage(failing.getErr());
			failing.getErr().flush();
			return ExitCode.USAGE;
		});

		return cmd;
	}

	private static void addStandardHelpRecursively(CommandLine command) {
		for (CommandLine sub : command.getSubcommands().values()) {
			sub.getCommandSpec().mixinStandardHelpOptions(true);
			addStandardHelpRecursively(sub);
		}
	}
}
