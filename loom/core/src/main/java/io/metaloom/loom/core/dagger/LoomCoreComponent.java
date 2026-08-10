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
import io.metaloom.loom.db.jooq.dagger.JooqIntegrityBindModule;
import io.metaloom.loom.db.jooq.dagger.JooqStorageStatsBindModule;
import io.metaloom.loom.db.jooq.dagger.JooqModule;
import io.metaloom.loom.mcp.dagger.MCPModule;
import io.metaloom.loom.mcp.dagger.MCPToolModule;
import io.metaloom.loom.monitoring.dagger.MonitoringModule;
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
	JooqIntegrityBindModule.class,
	JooqStorageStatsBindModule.class,
	EndpointModule.class,
	RESTBindModule.class,
	RESTModule.class,
	MCPModule.class,
	MCPToolModule.class,
	MonitoringModule.class,
	MemoryToolModule.class,
	MemoryModule.class,
	SandboxModule.class,
	ChatEndpointModule.class,
	RoutingContextModule.class,
	SearchModule.class,
	SimilarityModule.class,
	VectorIndexModule.class })
public interface LoomCoreComponent {

	DaoCollection daos();

	BootstrapInitializer boot();

	io.metaloom.loom.agent.chat.AgentService agentService();

	AuthenticationService authService();

	io.metaloom.loom.server.grpc.GrpcService grpcService();

	/**
	 * The live engines of runs that are currently executing.
	 *
	 * <p>
	 * Exposed so an endpoint test can register a real engine for a run it created, which is the only
	 * way to exercise the routes that require one - breakpoints, stepping, resume. Without it those
	 * routes could only ever be tested down the "no live engine" branch, which is the half that does
	 * not do anything.
	 * </p>
	 */
	io.metaloom.loom.rest.service.impl.PipelineRunRegistry pipelineRunRegistry();

	/** The UI events socket, so a test can observe the frames a run emits. */
	io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster pipelineEventBroadcaster();

	/**
	 * The database integrity checks.
	 *
	 * <p>
	 * Exposed so an endpoint test can corrupt the database on purpose and then assert the endpoint
	 * reports it. Nothing in production should reach for this - the REST layer injects
	 * {@code DbIntegrityService} the ordinary way.
	 * </p>
	 */
	io.metaloom.loom.db.integrity.DbIntegrityService dbIntegrity();

	@Component.Builder
	interface Builder {

		/**
		 * Inject configuration options.
		 *
		 * @param lookup the options source the component resolves configuration through
		 * @return this builder
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
