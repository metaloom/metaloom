package io.metaloom.loom.server.grpc.impl;

import java.util.UUID;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.proto.AssetRequest;
import io.metaloom.loom.proto.AssetResponse;
import io.metaloom.loom.server.grpc.GrpcServiceException;
import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.grpc.common.GrpcStatus;

/**
 * Business logic behind the {@code asset.AssetLoader} gRPC service.
 *
 * <p>
 * The DAO layer is blocking, so all database work is dispatched onto a worker thread instead of running on the event
 * loop which serves the gRPC connections.
 */
@Singleton
public class GrpcAssetLoader {

	private final DaoCollection daos;
	private final Vertx vertx;

	@Inject
	public GrpcAssetLoader(Vertx vertx, DaoCollection daos) {
		this.vertx = vertx;
		this.daos = daos;
	}

	/**
	 * Create or update the asset identified by the SHA-512 checksum of the request.
	 *
	 * @param creatorUuid
	 *            uuid of the authenticated user which is recorded as creator of newly created assets
	 * @param request
	 * @return
	 */
	public Future<AssetResponse> store(UUID creatorUuid, AssetRequest request) {
		return vertx.executeBlocking(() -> {
			SHA512 sha512sum = requireSHA512(request);

			Asset asset = daos.assetDao().loadBySHA512(sha512sum);
			if (asset == null) {
				asset = daos.assetDao().createAsset(creatorUuid, sha512sum, emptyToNull(request.getMimeType()),
					emptyToNull(request.getFilename()), emptyToNull(request.getInitialOrigin()), request.getSize());
			} else {
				update(request.getMimeType(), asset::setMimeType);
				update(request.getFilename(), asset::setFilename);
				update(request.getInitialOrigin(), asset::setInitialOrigin);
				if (request.getSize() != 0) {
					asset.setSize(request.getSize());
				}
			}

			// Optional hashes - only overwrite what the client actually sent
			if (!request.getSha256Sum().isEmpty()) {
				asset.setSHA256(SHA256.fromString(request.getSha256Sum()));
			}
			if (!request.getMd5Sum().isEmpty()) {
				asset.setMD5(MD5.fromString(request.getMd5Sum()));
			}
			if (!request.getChunkHash().isEmpty()) {
				asset.setChunkHash(ChunkHash.fromString(request.getChunkHash()));
			}
			if (request.getZeroChunkCount() != 0) {
				asset.setZeroChunkCount(request.getZeroChunkCount());
			}

			daos.assetDao().store(asset);
			return toResponse(asset, request.getFingerprint());
		});
	}

	/**
	 * Load the asset identified by the SHA-512 checksum of the request.
	 *
	 * @param request
	 * @return future which fails with {@link GrpcStatus#NOT_FOUND} when no such asset exists
	 */
	public Future<AssetResponse> load(AssetRequest request) {
		return vertx.executeBlocking(() -> {
			SHA512 sha512sum = requireSHA512(request);

			Asset asset = daos.assetDao().loadBySHA512(sha512sum);
			if (asset == null) {
				throw new GrpcServiceException(GrpcStatus.NOT_FOUND, "No asset found for the given SHA-512 checksum");
			}
			return toResponse(asset, request.getFingerprint());
		});
	}

	private SHA512 requireSHA512(AssetRequest request) {
		String sha512sum = request.getSha512Sum();
		if (sha512sum == null || sha512sum.isEmpty()) {
			throw new GrpcServiceException(GrpcStatus.INVALID_ARGUMENT, "The sha512sum field is mandatory");
		}
		try {
			return SHA512.fromString(sha512sum);
		} catch (RuntimeException e) {
			throw new GrpcServiceException(GrpcStatus.INVALID_ARGUMENT, "The sha512sum field is not a valid SHA-512 checksum", e);
		}
	}

	private AssetResponse toResponse(Asset asset, String fingerprint) {
		AssetResponse.Builder builder = AssetResponse.newBuilder()
			.setUuid(str(asset.getUuid()))
			.setFilename(str(asset.getFilename()))
			.setInitialOrigin(str(asset.getInitialOrigin()))
			.setMimeType(str(asset.getMimeType()))
			.setSize(asset.getSize())
			.setSha512Sum(str(asset.getSHA512()))
			.setSha256Sum(str(asset.getSHA256()))
			.setMd5Sum(str(asset.getMD5()))
			.setChunkHash(str(asset.getChunkHash()))
			.setZeroChunkCount(asset.getZeroChunkCount());

		// The fingerprint is not persisted with the asset - echo back what the client sent
		if (fingerprint != null) {
			builder.setFingerprint(fingerprint);
		}
		return builder.build();
	}

	/**
	 * Protobuf string fields default to the empty string and cannot hold null.
	 */
	private String str(Object value) {
		return value == null ? "" : value.toString();
	}

	private String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	private void update(String value, Consumer<String> setter) {
		if (value != null && !value.isEmpty()) {
			setter.accept(value);
		}
	}

}
