package io.metaloom.loom.mcp.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.auth.MCPAuthenticationHandler;
import io.metaloom.loom.auth.WebSocketAuthenticator;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

/**
 * Dagger module providing core MCP infrastructure beans.
 */
@Module
public class MCPModule {

	@Provides
	@Singleton
	@javax.inject.Named("mcpRouter")
	public Router mcpRouter(Vertx vertx) {
		return Router.router(vertx);
	}

	@Provides
	@Singleton
	public MCPAuthenticationHandler mcpAuthenticationHandler(
			io.metaloom.loom.auth.LoomAuthenticationHandler authHandler,
			io.metaloom.loom.db.model.token.TokenDao tokenDao,
			io.metaloom.loom.api.options.LoomOptions options) {
		return new MCPAuthenticationHandler(authHandler, tokenDao, options);
	}

	@Provides
	@Singleton
	public WebSocketAuthenticator webSocketAuthenticator(
			io.metaloom.loom.auth.LoomAuthenticationHandler authHandler,
			io.metaloom.loom.api.options.LoomOptions options) {
		return new WebSocketAuthenticator(authHandler, options);
	}

}
