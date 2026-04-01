package io.metaloom.loom.mcp.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
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

}
