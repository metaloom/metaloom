package io.metaloom.loom.client.grpc;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.grpc.impl.LoomGRPCClientImpl;
import io.metaloom.loom.client.grpc.method.AssetMethods;

public interface LoomGRPCClient extends AssetMethods, ClientSettings, LoomClient {

	static LoomGRPCClientImpl.Builder builder() {
		return LoomGRPCClientImpl.builder();
	}

	// TODO add version info
	String USER_AGENT = "Loom gRPC Client";

}
