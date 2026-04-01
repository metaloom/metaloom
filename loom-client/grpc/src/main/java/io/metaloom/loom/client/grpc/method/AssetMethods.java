package io.metaloom.loom.client.grpc.method;

import static io.metaloom.loom.client.grpc.InternalGrpcUtil.assetsAsyncStub;
import static io.metaloom.loom.client.grpc.InternalGrpcUtil.assetsStub;

import java.util.Objects;

import io.metaloom.loom.client.grpc.ClientSettings;
import io.metaloom.loom.proto.AssetRequest;
import io.metaloom.loom.proto.AssetResponse;
import io.metaloom.utils.hash.SHA512;

public interface AssetMethods extends ClientSettings {

	default LoomClientRequest<AssetResponse> loadAsset(SHA512 sha512sum) {
		Objects.requireNonNull(sha512sum, "Hashsum must be specified");

		AssetRequest.Builder request = AssetRequest.newBuilder()
			.setSha512Sum(sha512sum.toString());

		return request(
			() -> assetsStub(this).load(request.build()),
			() -> assetsAsyncStub(this).load(request.build()));
	}

	default LoomClientRequest<AssetResponse> storeAsset(AssetRequest request) {
		return request(
			() -> assetsStub(this).store(request),
			() -> assetsAsyncStub(this).store(request));
	}

}
