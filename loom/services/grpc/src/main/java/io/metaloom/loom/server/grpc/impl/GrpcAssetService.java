package io.metaloom.loom.server.grpc.impl;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.metaloom.loom.proto.AssetProto;
import io.metaloom.loom.proto.AssetRequest;
import io.metaloom.loom.proto.AssetResponse;
import io.metaloom.loom.server.grpc.GrpcAuthenticator;
import io.metaloom.loom.server.grpc.GrpcHandlers;
import io.metaloom.loom.server.grpc.GrpcServiceException;
import io.metaloom.loom.server.grpc.LoomGrpcService;
import io.vertx.ext.auth.User;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.server.GrpcServer;

/**
 * Exposes the {@code asset.AssetLoader} protobuf service. Both calls require an authenticated user.
 */
@Singleton
public class GrpcAssetService implements LoomGrpcService {

	private final GrpcAuthenticator authenticator;
	private final GrpcAssetLoader assetLoader;

	@Inject
	public GrpcAssetService(GrpcAuthenticator authenticator, GrpcAssetLoader assetLoader) {
		this.authenticator = authenticator;
		this.assetLoader = assetLoader;
	}

	@Override
	public ServiceDescriptor descriptor() {
		return AssetProto.getDescriptor().findServiceByName("AssetLoader");
	}

	@Override
	public void register(GrpcServer server) {
		ServiceMethod<AssetRequest, AssetResponse> store = method("Store", AssetRequest.getDefaultInstance());
		ServiceMethod<AssetRequest, AssetResponse> load = method("Load", AssetRequest.getDefaultInstance());

		server.callHandler(store, GrpcHandlers.authenticated(authenticator,
			(user, request) -> assetLoader.store(userUuid(user), request)));

		server.callHandler(load, GrpcHandlers.authenticated(authenticator,
			(user, request) -> assetLoader.load(request)));
	}

	/**
	 * Extract the loom user uuid which the authentication service embeds as {@code uuid} claim into the JWT.
	 */
	private UUID userUuid(User user) {
		String uuid = user.principal().getString("uuid");
		if (uuid == null) {
			throw new GrpcServiceException(GrpcStatus.UNAUTHENTICATED, "Token does not carry a user uuid");
		}
		try {
			return UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			throw new GrpcServiceException(GrpcStatus.UNAUTHENTICATED, "Token carries a malformed user uuid", e);
		}
	}

}
