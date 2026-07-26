package io.metaloom.cli.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cli.CliContext;
import io.metaloom.cli.client.LoomApi;
import io.metaloom.cli.client.LoomApiRestImpl;
import io.metaloom.cli.client.TokenResolver;
import io.metaloom.cli.config.CliConfigLoader;
import io.metaloom.cli.config.CliPaths;
import io.metaloom.cli.config.CredentialStore;
import io.metaloom.cli.config.ServerUrl;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomHttpClientImpl;

/**
 * Everything the CLI needs that is not a command.
 *
 * <p>The client is <em>not</em> a singleton and is only reachable through a
 * {@code Provider}: it must not be constructed until {@code CliExecutionStrategy} has
 * resolved the configuration, or it would be built against defaults. That constraint is what
 * lets Dagger build the object graph in one pass, without the throwaway parse
 * {@code CortexCLIMain} needs.</p>
 */
@Module
public abstract class CliModule {

	@Provides
	@Singleton
	static CliPaths paths(CliConfigLoader loader, CliContext context) {
		return loader.paths(context);
	}

	@Provides
	@Singleton
	static CredentialStore credentialStore(CliPaths paths) {
		return new CredentialStore(paths.credentialsFile());
	}

	@Provides
	static LoomHttpClient httpClient(CliContext context, TokenResolver tokens) {
		ServerUrl server = ServerUrl.parse(context.serverUri().toString());
		LoomHttpClientImpl client = LoomHttpClient.builder()
			.setScheme(server.scheme())
			.setHostname(server.host())
			.setPort(server.port())
			.setPathPrefix(server.pathPrefix())
			.setConnectTimeout(context.getTimeout())
			.setReadTimeout(context.getTimeout())
			.setWriteTimeout(context.getTimeout())
			.build();
		// Not required: `login` and `health` work without one, so an absent token is only an
		// error when a command actually needs it.
		String token = tokens.resolve();
		if (token != null && !token.isBlank()) {
			client.setToken(token);
		}
		return client;
	}

	@Provides
	static LoomApi loomApi(LoomHttpClient client) {
		return new LoomApiRestImpl(client);
	}
}
