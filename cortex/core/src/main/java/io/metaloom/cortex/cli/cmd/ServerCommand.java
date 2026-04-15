package io.metaloom.cortex.cli.cmd;

import static io.metaloom.cortex.cli.ExitCode.ERROR;
import static io.metaloom.cortex.cli.ExitCode.OK;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.Cortex;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Singleton
@Command(name = "server", aliases = { "s" }, description = "Server command")
public class ServerCommand extends AbstractLoomWorkerCommand {

	public static final Logger log = LoggerFactory.getLogger(ServerCommand.class);

	private final Cortex cortex;

	@Inject
	public ServerCommand(Cortex cortex) {
		this.cortex = cortex;
	}

	@Command(name = "start", description = "Start the cortex server")
	public int run(
		@Option(names = { "-a", "--actions" }, description = "Actions to be used when processing files.") String enabledNodes) {
		try {
			try {
				cortex.run();
			} catch (Throwable t) {
				log.error("Error while starting Cortex. Invoking shutdown.", t);
				cortex.shutdownAndTerminate(10);
			}
			return OK.code();
		} catch (Exception e) {
			log.error("Restoring collections failed.", e);
			return ERROR.code();
		}
	}

}
