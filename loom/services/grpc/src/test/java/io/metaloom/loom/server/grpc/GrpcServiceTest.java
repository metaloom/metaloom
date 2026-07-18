package io.metaloom.loom.server.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.metaloom.loom.proto.AssetRequest;
import io.metaloom.loom.proto.AssetResponse;
import io.metaloom.loom.proto.health.HealthCheckRequest;
import io.metaloom.loom.proto.health.HealthCheckResponse;
import io.metaloom.loom.proto.health.HealthCheckResponse.ServingStatus;
import io.metaloom.loom.proto.reflection.ServerReflectionRequest;
import io.metaloom.loom.proto.reflection.ServerReflectionResponse;
import io.metaloom.loom.proto.reflection.ServiceResponse;
import io.metaloom.loom.server.grpc.impl.GrpcAssetLoader;
import io.metaloom.loom.server.grpc.impl.GrpcAssetService;
import io.metaloom.loom.server.grpc.impl.GrpcHealthService;
import io.metaloom.loom.server.grpc.impl.GrpcReflectionService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientRequest;
import io.vertx.grpc.client.GrpcClientResponse;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;

/**
 * Boots the gRPC server on an ephemeral port and exercises it with a real gRPC client.
 *
 * <p>
 * The asset calls are only checked up to the authentication gate - exercising them end-to-end requires a database and is
 * covered by the endpoint tests in the core module.
 */
public class GrpcServiceTest {

	private static final String VALID_TOKEN = "valid-token";

	private Vertx vertx;
	private GrpcService grpcService;
	private GrpcClient client;

	@BeforeEach
	public void setup() {
		vertx = Vertx.vertx();

		LoomOptions options = new LoomOptions();
		options.getServer().setBindAddress("localhost");
		// Let the OS pick a free port so the test does not collide with a running server
		options.getServer().setGrpcPort(0);

		GrpcAuthenticator authenticator = new GrpcAuthenticator(new StubAuthHandler());
		GrpcAssetService assetService = new GrpcAssetService(authenticator, new GrpcAssetLoader(vertx, null));

		grpcService = new GrpcService(vertx, options, assetService, new GrpcHealthService(), new GrpcReflectionService());
		grpcService.start();

		client = GrpcClient.client(vertx);
	}

	@AfterEach
	public void teardown() {
		if (client != null) {
			client.close().toCompletionStage().toCompletableFuture().join();
		}
		if (grpcService != null) {
			grpcService.stop();
		}
		if (vertx != null) {
			vertx.close().toCompletionStage().toCompletableFuture().join();
		}
	}

	@Test
	public void testServerListensOnEphemeralPort() {
		assertTrue(grpcService.port() > 0, "The server should have bound to a port");
	}

	@Test
	public void testHealthCheckReportsServing() {
		GrpcClientResponse<HealthCheckRequest, HealthCheckResponse> response = call(healthMethod("Check"),
			HealthCheckRequest.newBuilder().build());

		// The status of a successful call only becomes available once the trailers have been read
		HealthCheckResponse message = await(response.last());
		assertEquals(GrpcStatus.OK, response.status());
		assertEquals(ServingStatus.SERVING, message.getStatus());
	}

	@Test
	public void testHealthCheckOfUnknownServiceIsNotFound() {
		GrpcClientResponse<HealthCheckRequest, HealthCheckResponse> response = call(healthMethod("Check"),
			HealthCheckRequest.newBuilder().setService("does.not.Exist").build());

		assertEquals(GrpcStatus.NOT_FOUND, response.status());
	}

	@Test
	public void testReflectionListsAllServices() {
		ServiceMethod<ServerReflectionResponse, ServerReflectionRequest> method = ServiceMethod.client(
			ServiceName.create("grpc.reflection.v1.ServerReflection"), "ServerReflectionInfo",
			GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(ServerReflectionResponse.getDefaultInstance()));

		GrpcClientResponse<ServerReflectionRequest, ServerReflectionResponse> response = call(method,
			ServerReflectionRequest.newBuilder().setListServices("*").build());

		ServerReflectionResponse reflection = await(response.last());
		assertEquals(GrpcStatus.OK, response.status());

		List<String> names = new ArrayList<>();
		for (ServiceResponse service : reflection.getListServicesResponse().getServiceList()) {
			names.add(service.getName());
		}

		assertTrue(names.contains("asset.AssetLoader"), "Missing asset service in " + names);
		assertTrue(names.contains("grpc.health.v1.Health"), "Missing health service in " + names);
		assertTrue(names.contains("grpc.reflection.v1.ServerReflection"), "Missing reflection service in " + names);
	}

	@Test
	public void testReflectionResolvesSymbolToDescriptor() {
		ServiceMethod<ServerReflectionResponse, ServerReflectionRequest> method = ServiceMethod.client(
			ServiceName.create("grpc.reflection.v1.ServerReflection"), "ServerReflectionInfo",
			GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(ServerReflectionResponse.getDefaultInstance()));

		GrpcClientResponse<ServerReflectionRequest, ServerReflectionResponse> response = call(method,
			ServerReflectionRequest.newBuilder().setFileContainingSymbol("asset.AssetLoader").build());

		ServerReflectionResponse reflection = await(response.last());
		assertEquals(1, reflection.getFileDescriptorResponse().getFileDescriptorProtoCount(),
			"Expected the asset.proto descriptor to be returned");
	}

	@Test
	public void testAssetCallWithoutTokenIsUnauthenticated() {
		GrpcClientResponse<AssetRequest, AssetResponse> response = call(assetMethod("Load"), AssetRequest.newBuilder().build(), null);
		assertEquals(GrpcStatus.UNAUTHENTICATED, response.status());
	}

	@Test
	public void testAssetCallWithInvalidTokenIsUnauthenticated() {
		GrpcClientResponse<AssetRequest, AssetResponse> response = call(assetMethod("Load"), AssetRequest.newBuilder().build(),
			"Bearer nope");
		assertEquals(GrpcStatus.UNAUTHENTICATED, response.status());
	}

	@Test
	public void testAssetCallWithValidTokenPassesAuthGate() {
		// No sha512sum is set, so the request is rejected by validation - which proves the call got past authentication
		GrpcClientResponse<AssetRequest, AssetResponse> response = call(assetMethod("Load"), AssetRequest.newBuilder().build(),
			"Bearer " + VALID_TOKEN);
		assertEquals(GrpcStatus.INVALID_ARGUMENT, response.status());
	}

	private ServiceMethod<HealthCheckResponse, HealthCheckRequest> healthMethod(String name) {
		return ServiceMethod.client(ServiceName.create("grpc.health.v1.Health"), name,
			GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(HealthCheckResponse.getDefaultInstance()));
	}

	private ServiceMethod<AssetResponse, AssetRequest> assetMethod(String name) {
		return ServiceMethod.client(ServiceName.create("asset.AssetLoader"), name,
			GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(AssetResponse.getDefaultInstance()));
	}

	private <Req, Resp> GrpcClientResponse<Req, Resp> call(ServiceMethod<Resp, Req> method, Req message) {
		return call(method, message, "Bearer " + VALID_TOKEN);
	}

	private <Req, Resp> GrpcClientResponse<Req, Resp> call(ServiceMethod<Resp, Req> method, Req message, String authorization) {
		SocketAddress address = SocketAddress.inetSocketAddress(grpcService.port(), "localhost");
		GrpcClientRequest<Req, Resp> request = await(client.request(address, method));
		if (authorization != null) {
			request.headers().set("authorization", authorization);
		}
		return await(request.send(message));
	}

	private <T> T await(Future<T> future) {
		try {
			return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException("Failed to await future", e);
		}
	}

	/**
	 * Accepts a single well known token so that the tests do not need a keystore or a database.
	 */
	private static class StubAuthHandler implements LoomAuthenticationHandler {

		@Override
		public void handle(RoutingContext event) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Future<User> authenticateToken(String token) {
			if (!VALID_TOKEN.equals(token)) {
				return Future.failedFuture(new IllegalStateException("Invalid token"));
			}
			User user = User.create(new JsonObject().put("uuid", java.util.UUID.randomUUID().toString()));
			assertNotNull(user);
			return Future.succeededFuture(user);
		}
	}

}
