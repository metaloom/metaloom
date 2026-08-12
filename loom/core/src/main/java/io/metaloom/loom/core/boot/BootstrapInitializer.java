package io.metaloom.loom.core.boot;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.sandbox.SandboxReaper;
import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.rest.search.IndexJobRegistry;
import io.metaloom.loom.rest.search.SearchEmbeddingDrainer;
import io.metaloom.loom.rest.storage.StorageSpaceMonitor;
import io.metaloom.loom.rest.vector.EmbeddingIndexDrainer;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.mcp.MCPService;
import io.metaloom.loom.monitoring.MonitoringService;
import io.metaloom.loom.rest.RESTService;
import io.metaloom.loom.rest.UIService;
import io.metaloom.loom.rest.service.impl.AssetPipelineTrigger;
import io.metaloom.loom.server.grpc.GrpcService;
import io.vertx.core.http.HttpServer;

@Singleton
public class BootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);

	private final GrpcService grpcService;

	private final RESTService restService;

	private final UIService uiService;

	private final MCPService mcpService;

	private final MonitoringService monitoringService;

	private final AuthenticationService authService;

	private final Flyway flyway;

	private final DatabaseInitializer initializer;

	private final DemoDatabaseInitializer demoInitializer;

	private final HttpServer httpServer;

	private final AssetPipelineTrigger assetPipelineTrigger;

	private final SandboxReaper sandboxReaper;

	private final EmbeddingIndexDrainer embeddingIndexDrainer;

	private final SearchEmbeddingDrainer searchEmbeddingDrainer;

	private final IndexJobRegistry indexJobRegistry;

	private final StorageSpaceMonitor storageSpaceMonitor;

	private final DataSource dataSource;

	private final SimilarityIndex similarityIndex;

	private final VectorIndex vectorIndex;

	@Inject
	public BootstrapInitializer(GrpcService grpcService, RESTService restService, UIService uiService, MCPService mcpService,
		MonitoringService monitoringService, AuthenticationService authService,
		Flyway flyway, DatabaseInitializer initializer, DemoDatabaseInitializer demoInitializer, HttpServer httpServer,
		AssetPipelineTrigger assetPipelineTrigger, SandboxReaper sandboxReaper, EmbeddingIndexDrainer embeddingIndexDrainer,
		SearchEmbeddingDrainer searchEmbeddingDrainer, IndexJobRegistry indexJobRegistry, StorageSpaceMonitor storageSpaceMonitor,
		DataSource dataSource, SimilarityIndex similarityIndex, VectorIndex vectorIndex) {
		this.grpcService = grpcService;
		this.restService = restService;
		this.uiService = uiService;
		this.mcpService = mcpService;
		this.monitoringService = monitoringService;
		this.authService = authService;
		this.flyway = flyway;
		this.initializer = initializer;
		this.demoInitializer = demoInitializer;
		this.httpServer = httpServer;
		this.assetPipelineTrigger = assetPipelineTrigger;
		this.sandboxReaper = sandboxReaper;
		this.embeddingIndexDrainer = embeddingIndexDrainer;
		this.searchEmbeddingDrainer = searchEmbeddingDrainer;
		this.indexJobRegistry = indexJobRegistry;
		this.storageSpaceMonitor = storageSpaceMonitor;
		this.dataSource = dataSource;
		this.similarityIndex = similarityIndex;
		this.vectorIndex = vectorIndex;
	}

	public void init(boolean migrate) throws IOException {
		if (migrate) {
			try {
				log.info("Invoking database migration check");
				flyway.migrate();
			} catch (Exception e) {
				throw new RuntimeException("Error while invoking database migration", e);
			}
		}

		try {
			log.info("Invoking database initializer");
			initializer.init();
		} catch (Exception e) {
			throw new RuntimeException("Error while initializing database", e);
		}

		try {
			log.info("Invoking demo database initializer");
			demoInitializer.init();
		} catch (Exception e) {
			log.warn("Error while populating demo data — continuing startup", e);
		}

		// try {
		// authService.init();
		// } catch (Exception e) {
		// throw new RuntimeException("Error while initializing the authentication service", e);
		// }

		try {
			log.info("Starting REST service");
			restService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting rest service", e);
		}

		try {
			log.info("Starting UI service");
			uiService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting UI service", e);
		}

		try {
			log.info("Registering asset pipeline trigger");
			assetPipelineTrigger.register();
		} catch (Exception e) {
			throw new RuntimeException("Error while registering asset pipeline trigger", e);
		}

		try {
			log.info("Starting HTTP server");
			httpServer.listen().toCompletionStage().toCompletableFuture().get();
			log.info("HTTP server listening on port {}", httpServer.actualPort());
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException("Error while starting HTTP server", e);
		}

		try {
			log.info("Starting MCP service");
			mcpService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting MCP service", e);
		}

		try {
			log.info("Starting monitoring service");
			monitoringService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting monitoring service", e);
		}

		try {
			log.info("Starting gRPC service");
			grpcService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting gRPC service", e);
		}

		try {
			log.info("Starting coding sandbox reaper");
			sandboxReaper.start();
		} catch (Exception e) {
			log.warn("Error while starting the coding sandbox reaper — continuing startup", e);
		}

		try {
			embeddingIndexDrainer.start();
			searchEmbeddingDrainer.start();
		} catch (Exception e) {
			log.warn("Error while starting the vector index drain — continuing startup", e);
		}

		try {
			storageSpaceMonitor.start();
		} catch (Exception e) {
			// A capacity observer that cannot start must not stop the server from serving. The report
			// endpoint computes its own figures on request and is unaffected.
			log.warn("Error while starting the storage space monitor — continuing startup", e);
		}
	}

	public RESTService getRestService() {
		return restService;
	}

	public MCPService getMcpService() {
		return mcpService;
	}

	public GrpcService getGrpcService() {
		return grpcService;
	}

	public void deinit() {
		sandboxReaper.stop();
		embeddingIndexDrainer.stop();
		searchEmbeddingDrainer.stop();
		storageSpaceMonitor.stop();
		// Releases the index-job worker pool. A job still running at shutdown is abandoned rather
		// than awaited - every one of them is restartable work over derived data, so the right
		// trade is a prompt shutdown and a button press afterwards.
		indexJobRegistry.close();
		monitoringService.stop();
		mcpService.stop();
		restService.stop();
		grpcService.stop();
		// Stopping the services unregisters their handlers but leaves the socket bound.
		// A process that starts a server, shuts it down and starts another - which is
		// what a test suite does - then fails to bind with "Address already in use".
		try {
			httpServer.close().toCompletionStage().toCompletableFuture().get();
			log.info("HTTP server closed");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while closing the HTTP server");
		} catch (Exception e) {
			log.error("Failed to close the HTTP server", e);
		}

		// Release the Lucene indices, now that the HTTP server no longer accepts requests and the drainers above have
		// stopped feeding them. An IndexWriter holds an exclusive write.lock on its directory and only releases it when
		// closed - at an abrupt stop the lock file and any uncommitted segments are left behind, and in a test suite the
		// next server to boot on the same directory silently degrades to the Noop implementation. See
		// spec/loom/SEARCH_LUCENE.md §4.3 and spec/concept/CLUSTERING.md §3.
		closeIndex(similarityIndex, similarityIndex::commit, "fingerprint similarity index");
		closeIndex(vectorIndex, vectorIndex::commit, "vector index");

		// Release the JDBC connection pool. Without this the pool keeps its minPoolSize connections
		// open forever: the pool is a @Singleton of the Dagger component, and a component that is
		// discarded takes no action on the connections it opened. In production that leaks once, at
		// shutdown, and the exiting process cleans up after it. In a test suite it is fatal - every
		// test method builds a fresh component, so the leak is per-method and cumulative, and around
		// the twentieth boot PostgreSQL answers "FATAL: sorry, too many clients already" while the
		// test that happens to be running takes the blame.
		//
		// c3p0's PooledDataSource extends AutoCloseable, so this needs no c3p0 dependency here.
		if (dataSource instanceof AutoCloseable closeable) {
			try {
				closeable.close();
				log.info("Database connection pool closed");
			} catch (Exception e) {
				log.error("Failed to close the database connection pool", e);
			}
		}
	}

	/**
	 * Commit and close one search index, if the bound implementation has anything to close.
	 *
	 * <p>
	 * The {@code instanceof AutoCloseable} check is deliberately the seam rather than a {@code close()} on the SPI: the Noop implementations hold no
	 * resources and an external vector service would not either, so only the embedded Lucene backends need this. A failure here is logged and swallowed
	 * - the rest of the shutdown sequence still has to run.
	 * </p>
	 */
	private void closeIndex(Object index, Runnable commit, String what) {
		if (!(index instanceof AutoCloseable closeable)) {
			return;
		}
		// Commit first: close() would flush anyway, but a failing flush inside close() loses the segments silently. It gets
		// its own try block because a failed commit must still be followed by the close - otherwise the write.lock this
		// whole step exists to release stays on disk.
		try {
			commit.run();
		} catch (Exception e) {
			log.error("Failed to commit the {} before shutdown", what, e);
		}
		try {
			closeable.close();
			log.info("The {} was closed", what);
		} catch (Exception e) {
			log.error("Failed to close the {}", what, e);
		}
	}
}
