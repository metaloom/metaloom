package io.metaloom.cli.cmd.infra;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.output.Table;
import picocli.CommandLine.Command;

@Singleton
@Command(name = "health", description = "Check whether the server is healthy.")
public class HealthCommand extends AbstractCliCommand {

	@Inject
	public HealthCommand() {
	}

	@Override
	protected Integer execute() {
		String status = api().health();
		printer().printOne(java.util.Map.of("status", status == null ? "unknown" : status),
			map -> new Table("STATUS").row(printer().ansi().status(String.valueOf(map.get("status")))),
			map -> String.valueOf(map.get("status")));
		// An unhealthy server is a failure, not a result: `metaloom health && deploy` must not
		// proceed when the server reports itself unwell. The server's vocabulary is
		// UP / DEGRADED (see HealthEndpoint), so only UP is a pass.
		return "UP".equalsIgnoreCase(status) ? ExitCode.OK : ExitCode.SERVER_FAILURE;
	}
}
