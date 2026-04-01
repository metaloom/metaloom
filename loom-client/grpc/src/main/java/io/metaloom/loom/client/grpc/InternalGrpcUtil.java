package io.metaloom.loom.client.grpc;

import io.metaloom.loom.proto.AssetLoaderGrpc;

public final class InternalGrpcUtil {

	private InternalGrpcUtil() {
	}

	public static AssetLoaderGrpc.AssetLoaderBlockingStub assetsStub(ClientSettings client) {
		return AssetLoaderGrpc.newBlockingStub(client.channel()).withInterceptors(new ClientJWTInterceptor(client));
	}

	public static AssetLoaderGrpc.AssetLoaderFutureStub assetsAsyncStub(ClientSettings client) {
		return AssetLoaderGrpc.newFutureStub(client.channel());
	}

}