package io.metaloom.cli.cmd.auth;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.config.CredentialStore;
import picocli.CommandLine.Command;

@Singleton
@Command(name = "logout", description = "Discard the stored token for the active profile.")
public class LogoutCommand extends AbstractCliCommand {

	private final CredentialStore credentials;

	@Inject
	public LogoutCommand(CredentialStore credentials) {
		this.credentials = credentials;
	}

	@Override
	protected Integer execute() {
		String profile = context.profileName();
		boolean removed = credentials.remove(profile);
		printer().printMessage(removed
			? "Logged out of profile '" + profile + "'."
			: "No stored credentials for profile '" + profile + "'.");
		return ExitCode.OK;
	}
}
