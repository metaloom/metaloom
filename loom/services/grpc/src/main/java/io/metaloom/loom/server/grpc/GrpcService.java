package io.metaloom.loom.server.grpc;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.service.AbstractService;
import io.metaloom.loom.proto.AssetLoaderGrpc;
import io.metaloom.loom.proto.AssetRequest;
import io.metaloom.loom.proto.AssetResponse;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.grpc.server.GrpcServerResponse;
import io.vertx.grpc.server.auth.JWTGrpcServer;

@Singleton
public class GrpcService extends AbstractService {

	public static final Logger log = LoggerFactory.getLogger(GrpcService.class);

	private HttpServer server;
	private final JWTAuth auth;

	@Inject
	public GrpcService(Vertx vertx, LoomOptions options, JWTAuth auth) {
		super(vertx, options);
		this.auth = auth;
	}

	public void start() {
		int port = options().getServer().getGrpcPort();
		String host = options().getServer().getBindAddress();

		JWTGrpcServer jwtServer = JWTGrpcServer.create(vertx(), auth);
		jwtServer.callHandler(AssetLoaderGrpc.getLoadMethod(), true, request -> {
			request.handler(hello -> {
				User user = request.user();
				log.info("Server got asset with hash {}", hello.getSha512Sum());
				GrpcServerResponse<AssetRequest, AssetResponse> response = request.response();
				AssetResponse reply = AssetResponse.newBuilder()
					.setFilename("Hello " + hello.getSha512Sum() + " from " + user.subject())
					.build();
				response.end(reply);
			});
		});

		server = vertx().createHttpServer(new HttpServerOptions().setPort(0)
			.setHost("localhost")).requestHandler(jwtServer);

		log.info("Starting server");

		Future<HttpServer> fut = server.listen();
		HttpServer srv = fut.await();
		log.info("Server started and listening on port " + srv
			.actualPort());
	}

	public HttpServer getServer() {
		return server;
	}

	public int port() {
		return server.actualPort();
	}

	public void stop() {
		getServer().close();
	}

}
