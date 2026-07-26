package io.metaloom.cli.cmd.auth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.config.CredentialStore;
import io.metaloom.cli.config.CredentialStore.Credentials;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Authenticate and store the resulting token for the active profile.
 */
@Singleton
@Command(name = "login", description = "Log in to a Loom server and store the token.")
public class LoginCommand extends AbstractCliCommand {

	@Option(names = { "-u", "--username" }, description = "Username. Prompted for if omitted.")
	String username;

	@Option(names = { "-p", "--password" }, description = "Password. Prompted for if omitted.",
		arity = "0..1", interactive = true)
	char[] password;

	@Option(names = "--password-stdin", description = "Read the password from stdin. Use this in scripts.")
	boolean passwordStdin;

	@Option(names = "--api-token", paramLabel = "TOKEN",
		description = "Store this long-lived API token (from /api/v1/tokens) instead of logging in "
			+ "with a password. Preferred for CI, where a login JWT would expire.")
	String presetToken;

	private final CredentialStore credentials;

	@Inject
	public LoginCommand(CredentialStore credentials) {
		this.credentials = credentials;
	}

	@Override
	protected Integer execute() {
		String profile = context.profileName();

		if (presetToken != null && !presetToken.isBlank()) {
			// An API token from /api/v1/tokens - the right credential for CI, since it does
			// not expire the way a login JWT does.
			credentials.store(profile, new Credentials().setToken(presetToken));
			printer().printMessage("Token stored for profile '" + profile + "'.");
			return ExitCode.OK;
		}

		String user = username != null ? username : prompt("Username: ");
		String secret = resolvePassword();

		String token = api().login(user, secret);
		credentials.store(profile, new Credentials().setUsername(user).setToken(token));

		printer().printMessage("Logged in as " + user + " (profile '" + profile + "').");
		return ExitCode.OK;
	}

	private String resolvePassword() {
		if (passwordStdin) {
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
				String line = reader.readLine();
				if (line == null || line.isEmpty()) {
					throw new CliException(ExitCode.USAGE, "No password on stdin.");
				}
				return line;
			} catch (java.io.IOException e) {
				throw new CliException(ExitCode.FILE_ERROR, "Could not read the password from stdin.", null, e);
			}
		}
		if (password != null && password.length > 0) {
			return new String(password);
		}
		// picocli's interactive option already handles the echo-off prompt; reaching here
		// means neither form was supplied.
		char[] read = System.console() != null ? System.console().readPassword("Password: ") : null;
		if (read == null || read.length == 0) {
			throw new CliException(ExitCode.USAGE,
				"No password given. Pass --password, use --password-stdin, or run interactively.");
		}
		return new String(read);
	}

	private String prompt(String label) {
		if (System.console() == null) {
			throw new CliException(ExitCode.USAGE, "No username given and no terminal to prompt on. Pass --username.");
		}
		String value = System.console().readLine(label);
		if (value == null || value.isBlank()) {
			throw new CliException(ExitCode.USAGE, "A username is required.");
		}
		return value.trim();
	}
}
