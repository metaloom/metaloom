package io.metaloom.loom.core.dagger;

import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.agent.chat.dagger.ChatEndpointModule;
import io.metaloom.loom.agent.memory.dagger.MemoryModule;
import io.metaloom.loom.agent.memory.dagger.MemoryToolModule;
import io.metaloom.loom.agent.sandbox.dagger.SandboxModule;
import io.metaloom.loom.auth.jwt.AuthModule;
import io.metaloom.loom.common.dagger.LoomModule;
import io.metaloom.loom.common.dagger.VertxModule;
import io.metaloom.loom.core.boot.BootstrapInitializer;
import io.metaloom.loom.db.dagger.DBBindModule;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.flyway.dagger.FlywayModule;
import io.metaloom.loom.db.jooq.dagger.JooqLoomDaoBindModule;
import io.metaloom.loom.db.jooq.dagger.JooqModule;
import io.metaloom.loom.mcp.dagger.MCPModule;
import io.metaloom.loom.mcp.dagger.MCPToolModule;
import io.metaloom.loom.rest.dagger.EndpointModule;
import io.metaloom.loom.rest.dagger.RESTBindModule;
import io.metaloom.loom.rest.dagger.RESTModule;

/**
 * Central dagger loom component.
 */
@Singleton
@Component(modules = {
	VertxModule.class,
	LoomModule.class,
	AuthModule.class,
	AuthBindModule.class,
	FlywayModule.class,
	DBBindModule.class,
	JooqLoomDaoBindModule.class,
	JooqModule.class,
	EndpointModule.class,
	RESTBindModule.class,
	RESTModule.class,
	MCPModule.class,
	MCPToolModule.class,
	MemoryToolModule.class,
	MemoryModule.class,
	SandboxModule.class,
	ChatEndpointModule.class,
	RoutingContextModule.class })
public interface LoomCoreComponent {

	DaoCollection daos();

	BootstrapInitializer boot();

	io.metaloom.loom.agent.chat.AgentService agentService();

	AuthenticationService authService();

	io.metaloom.loom.server.grpc.GrpcService grpcService();

	@Component.Builder
	interface Builder {

		/**
		 * Inject configuration options.
		 * 
		 * @param options
		 * @return
		 */
		@BindsInstance
		Builder configuration(LoomOptionsLookup lookup);

		/**
		 * Build the component.
		 * 
		 * @return
		 */
		LoomCoreComponent build();

	}

}
