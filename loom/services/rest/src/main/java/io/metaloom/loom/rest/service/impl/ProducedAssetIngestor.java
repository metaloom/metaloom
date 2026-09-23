package io.metaloom.loom.rest.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Turns bytes Loom itself produced into a first-class asset.
 *
 * <p>
 * Loom has no byte-ingest endpoint for produced media, which is why {@code thumbnail},
 * {@code depthmap}, {@code tts}, {@code videogen} and {@code imagegen} all stop at a ledger row and
 * leave their output on the worker. This class does not solve that - the bytes still have to be in
 * this process to use it, so it serves callers running <em>inside</em> Loom, which today means the
 * MCP {@code generate_image} tool. The general fix is a REST-layer byte ingest; see
 * {@code spec/features/rest/REST_BINARY_HANDLING.md}.
 * </p>
 *
 * <p>
 * ⚠️ <strong>This is a second implementation of the sequence in
 * {@link AssetUploadEndpointService#register}</strong> (hash, store, dedup by SHA-512, one binary
 * row per library, publish). It is deliberately not a refactoring of that method: the endpoint
 * version is driven by a routing context and a multipart upload, has no unit test of its own, and
 * its relocation/reclaim branch has no caller here. The two must be changed together - if you touch
 * the ingest sequence in one, look at the other.
 * </p>
 */
@Singleton
public class ProducedAssetIngestor {

	private static final Logger log = LoggerFactory.getLogger(ProducedAssetIngestor.class);

	private final AssetEndpointService assetService;
	private final DaoCollection daos;
	private final AssetEventPublisher eventPublisher;
	private final BinaryStorageResolver storageResolver;
	private final StorageCapacityGuard capacityGuard;

	@Inject
	public ProducedAssetIngestor(AssetEndpointService assetService, DaoCollection daos, AssetEventPublisher eventPublisher,
		BinaryStorageResolver storageResolver, StorageCapacityGuard capacityGuard) {
		this.assetService = assetService;
		this.daos = daos;
		this.eventPublisher = eventPublisher;
		this.storageResolver = storageResolver;
		this.capacityGuard = capacityGuard;
	}

	/**
	 * Store {@code bytes} and return the asset they belong to, creating it when the content is new.
	 *
	 * <p>
	 * An asset <em>is</em> its content: {@code asset.sha512sum} is UNIQUE, so bytes Loom already
	 * holds resolve to the existing asset rather than failing on the unique violation. Generating
	 * the same image twice with a fixed seed is therefore idempotent, which is the behaviour a
	 * caller retrying a chat message wants.
	 * </p>
	 *
	 * @param userUuid the creator, which is why the calling tool must be identity-scoped
	 * @param libraryUuid the library the binary row is attached to
	 * @param bytes the produced file
	 * @param filename the name the asset carries
	 * @param mimeType the content type
	 * @param origin free-text provenance, e.g. the tool that produced it
	 * @return the asset, newly created or pre-existing
	 */
	public Asset ingest(UUID userUuid, UUID libraryUuid, byte[] bytes, String filename, String mimeType, String origin) throws IOException {
		UUID poolUuid = storageResolver.poolUuidOfLibrary(libraryUuid);
		BinaryStorage storage = storageResolver.forPool(poolUuid);
		capacityGuard.checkUpload(storage, bytes.length);

		// BinaryStorage.store takes a Path, not a byte[] - there is no stream overload - so produced
		// bytes have to touch the disk once on the way in. Deleted in the finally regardless of
		// outcome; store() has already copied them by then.
		Path staged = Files.createTempFile("loom-produced-", ".tmp");
		try {
			Files.write(staged, bytes);
			SHA512 sha512 = HashUtils.computeSHA512(staged);

			// Persist the bytes before recording anything in the DB, so a failure to store never
			// leaves a dangling asset pointing at a missing file.
			String locator = storage.store(staged, sha512, mimeType);

			Asset existing = daos.assetDao().loadBySHA512(sha512);
			boolean created = existing == null;
			Asset asset;
			if (created) {
				AssetCreateRequest request = new AssetCreateRequest();
				request.setFile(new FileInfo()
					.setFilename(filename)
					.setMimeType(mimeType)
					.setOrigin(origin)
					.setSize((long) bytes.length)
					.setFirstSeen(Instant.now()));
				request.setHashes(new HashInfo().setSHA512(sha512));
				asset = assetService.createAsset(userUuid, request);
			} else {
				asset = existing;
			}

			// Record where the bytes live so an asset-scoped pipeline run can locate the file on the
			// worker. One row per library.
			AssetBinary binary = daos.assetBinaryDao().loadByAssetAndLibrary(asset.getUuid(), libraryUuid);
			if (binary == null) {
				binary = daos.assetBinaryDao().createAssetBinary(locator, asset.getUuid(), userUuid, libraryUuid);
				binary.setMimeType(mimeType);
				binary.setPoolUuid(poolUuid);
				daos.assetBinaryDao().store(binary);
			}

			if (created) {
				// Let a matching pipeline pick this up. Published after the binary row exists so the
				// consumer can resolve the locator, and not published for content Loom already holds.
				eventPublisher.publishCreated(asset.getUuid(), mimeType);
			}

			log.info("{} produced asset {} ({} bytes, {}) stored at {} [{}]", created ? "Ingested" : "Reused existing", asset.getUuid(),
				bytes.length, mimeType, locator, storage.describe());
			return asset;
		} finally {
			Files.deleteIfExists(staged);
		}
	}
}
