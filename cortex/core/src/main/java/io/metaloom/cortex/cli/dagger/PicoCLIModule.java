package io.metaloom.cortex.cli.dagger;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.cli.CortexCLI;
import io.metaloom.cortex.cli.cmd.ProcessCommand;
import io.metaloom.cortex.cli.cmd.ServerCommand;
import picocli.CommandLine;

@Module
public abstract class PicoCLIModule {

	@Provides
	public static CommandLine commandLine(CortexCLI cli, ProcessCommand processCommand, ServerCommand serverCommand) {
		CommandLine cmd = new CommandLine(cli);
		cmd.addSubcommand("po", processCommand);
		cmd.addSubcommand("server", serverCommand);
		return cmd;
	}
}
