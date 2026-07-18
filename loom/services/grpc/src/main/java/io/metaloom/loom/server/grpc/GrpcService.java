package io.metaloom.loom.server.grpc;

import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.service.AbstractService;
import io.metaloom.loom.server.grpc.impl.GrpcAssetService;
import io.metaloom.loom.server.grpc.impl.GrpcHealthService;
import io.metaloom.loom.server.grpc.impl.GrpcReflectionService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.grpc.server.GrpcServer;

/**
 * Serves the loom gRPC API on its own HTTP server, configured via {@code ServerOptions.grpcPort} and
 * {@code ServerOptions.bindAddress}.
 */
@Singleton
public class GrpcService extends AbstractService {

	public static final Logger log = LoggerFactory.getLogger(GrpcService.class);

	private final GrpcAssetService assetService;
	private final GrpcHealthService healthService;
	private final GrpcReflectionService reflectionService;

	private HttpServer server;

	@Inject
	public GrpcService(Vertx vertx, LoomOptions options, GrpcAssetService assetService, GrpcHealthService healthService,
		GrpcReflectionService reflectionService) {
		super(vertx, options);
		this.assetService = assetService;
		this.healthService = healthService;
		this.reflectionService = reflectionService;
	}

	public void start() {
		int port = options().getServer().getGrpcPort();
		String host = options().getServer().getBindAddress();

		List<LoomGrpcService> services = List.of(assetService, healthService, reflectionService);

		// Reflection advertises every registered service, including itself
		reflectionService.setServices(services);

		GrpcServer grpcServer = GrpcServer.server(vertx());
		for (LoomGrpcService service : services) {
			log.info("Registering gRPC service {}", service.name().fullyQualifiedName());
			service.register(grpcServer);
		}

		server = vertx().createHttpServer(new HttpServerOptions()
			.setPort(port)
			.setHost(host))
			.requestHandler(grpcServer);

		log.info("Starting gRPC server on {}:{}", host, port);
		try {
			server.listen().toCompletionStage().toCompletableFuture().get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while starting the gRPC server", e);
		} catch (ExecutionException e) {
			throw new RuntimeException("Error while starting the gRPC server on " + host + ":" + port, e);
		}
		log.info("gRPC server started and listening on port {}", server.actualPort());
	}

	public HttpServer getServer() {
		return server;
	}

	/**
	 * Actual listen port. Differs from the configured port when port 0 was requested.
	 *
	 * @return the port or -1 when the server is not running
	 */
	public int port() {
		return server != null ? server.actualPort() : -1;
	}

	public GrpcHealthService getHealthService() {
		return healthService;
	}

	public void stop() {
		if (server != null) {
			log.info("Shutting down gRPC server");
			server.close();
			server = null;
		}
	}

}
