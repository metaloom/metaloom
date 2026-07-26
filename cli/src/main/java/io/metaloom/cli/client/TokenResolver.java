package io.metaloom.cli.client;

import java.io.IOException;
import java.nio.file.Files;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.CliContext;
import io.metaloom.cli.ExitCode;
import io.metaloom.cli.config.CredentialStore;

/**
 * Works out which bearer token to use.
 *
 * <p>Order: {@code --token} &gt; {@code --token-file} &gt; {@code METALOOM_TOKEN} (already
 * folded into the context by the config loader) &gt; the stored credentials for the active
 * profile.</p>
 */
@Singleton
public class TokenResolver {

	private final CliContext context;
	private final CredentialStore credentials;

	@Inject
	public TokenResolver(CliContext context, CredentialStore credentials) {
		this.context = context;
		this.credentials = credentials;
	}

	/** @return the token, or null when there is none */
	public String resolve() {
		if (context.getToken() != null && !context.getToken().isBlank()) {
			return context.getToken();
		}
		if (context.getTokenFile() != null) {
			try {
				String token = Files.readString(context.getTokenFile()).trim();
				if (token.isEmpty()) {
					throw new CliException(ExitCode.FILE_ERROR, "Token file " + context.getTokenFile() + " is empty.");
				}
				return token;
			} catch (IOException e) {
				throw new CliException(ExitCode.FILE_ERROR,
					"Could not read token file " + context.getTokenFile() + ": " + e.getMessage(), null, e);
			}
		}
		CredentialStore.Credentials stored;
		try {
			stored = credentials.load(context.profileName());
		} catch (CredentialStore.InsecureCredentialsException e) {
			throw new CliException(ExitCode.FILE_ERROR, e.getMessage(), null, e);
		}
		return stored == null ? null : stored.getToken();
	}

	/**
	 * @return the token
	 * @throws CliException {@link ExitCode#AUTH_REQUIRED} when there is none
	 */
	public String requireToken() {
		String token = resolve();
		if (token == null || token.isBlank()) {
			throw new CliException(ExitCode.AUTH_REQUIRED,
				"Not authenticated. Run 'metaloom login', or pass --token.");
		}
		return token;
	}
}
