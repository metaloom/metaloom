package io.metaloom.cli.cmd.auth;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.output.Table;
import io.metaloom.loom.rest.model.user.UserResponse;
import picocli.CommandLine.Command;

@Singleton
@Command(name = "whoami", description = "Show the user the current token belongs to.")
public class WhoamiCommand extends AbstractCliCommand {

	@Inject
	public WhoamiCommand() {
	}

	@Override
	protected Integer execute() {
		UserResponse me = api().me();
		printer().printOne(me,
			user -> new Table("UUID", "USERNAME", "EMAIL")
				.row(String.valueOf(user.getUuid()), user.getUsername(), user.getEmail()),
			user -> user.getUsername());
		return ExitCode.OK;
	}
}
