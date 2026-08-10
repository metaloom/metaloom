package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_POOL;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.ext.web.FileUpload;

/**
 * Handles multipart uploads that create an asset from real file bytes.
 *
 * <p>
 * The uploaded bytes are hashed, handed to the {@link BinaryStorage} the target library resolves to — the local upload directory, a filesystem pool
 * or an S3 bucket — and an {@code asset_location} row is recorded pointing at whatever locator that storage returned. For {@code POST /assets/upload}
 * an {@code asset.created} event is then published so a matching pipeline can be triggered.
 * </p>
 *
 * <p>
 * See {@code spec/features/rest/REST_BINARY_HANDLING.md} for the endpoint contract and
 * {@code spec/features/rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md} for the artefact write-back that builds on it.
 * </p>
 */
@Singleton
public class AssetUploadEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(AssetUploadEndpointService.class);

	private static final String DEFAULT_ORIGIN = "upload";

	private final AssetEndpointService assetService;
	private final DaoCollection daos;
	private final AssetEventPublisher eventPublisher;
	private final BinaryStorageResolver storageResolver;
	private final StorageCapacityGuard capacityGuard;

	@Inject
	public AssetUploadEndpointService(AssetEndpointService assetService, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, AssetEventPublisher eventPublisher, BinaryStorageResolver storageResolver,
		StorageCapacityGuard capacityGuard) {
		super(modelBuilder, validator);
		this.assetService = assetService;
		this.daos = daos;
		this.eventPublisher = eventPublisher;
		this.storageResolver = storageResolver;
		this.capacityGuard = capacityGuard;
	}

	/**
	 * Create an asset from a multipart file upload. Expects exactly one file part and a {@code libraryUuid} form field. Optional form fields:
	 * {@code origin} (defaults to {@code upload}) and {@code poolUuid}.
	 *
	 * <p>
	 * The target pool is normally dictated by the library ({@code library.pool_uuid}). Naming {@code poolUuid} explicitly overrides that for this one
	 * upload, which is an operator action — it writes bytes into a storage backend the caller picked rather than the one the library configures — so it
	 * additionally requires {@link io.metaloom.loom.db.model.perm.Permission#READ_ASSET_POOL}. That keeps pools an admin-facing concept while leaving
	 * plain uploading open to anyone holding {@link io.metaloom.loom.db.model.perm.Permission#CREATE_ASSET}.
	 * </p>
	 */
	public void upload(LoomRoutingContext lrc) {
		// Read before the permission check so the required permission set can depend on it.
		UUID explicitPool = optionalUuid(lrc, "poolUuid");
		Permission[] required = explicitPool == null
			? new Permission[] { CREATE_ASSET }
			: new Permission[] { CREATE_ASSET, READ_ASSET_POOL };

		checkPerms(lrc, () -> {
			FileUpload upload = singleUpload(lrc);
			UUID userUuid = lrc.userUuid();
			UUID libraryUuid = requiredLibraryUuid(lrc);
			String origin = formValue(lrc, "origin", DEFAULT_ORIGIN);

			String filename = upload.fileName();
			long size = upload.size();
			String mimeType = upload.contentType();

			UUID poolUuid = explicitPool != null ? explicitPool : storageResolver.poolUuidOfLibrary(libraryUuid);
			// Resolving an unknown pool is a 404 here rather than a silent fallback to local disk.
			BinaryStorage storage = storageResolver.forPool(poolUuid);
			capacityGuard.checkUpload(storage, size);

			SHA512 sha512 = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));

			// Persist the bytes before recording anything in the DB, so a failure to store never leaves a
			// dangling asset that points at a missing file.
			String locator = storage.store(Paths.get(upload.uploadedFileName()), sha512, mimeType);

			// An asset IS its content: asset.sha512sum is UNIQUE, so uploading bytes Loom already knows
			// must resolve to the existing asset. Creating unconditionally made every re-upload - the
			// same file sent twice, or imported into a second library - fail with a unique violation
			// surfacing as a 500.
			Asset existing = daos.assetDao().loadBySHA512(sha512);
			boolean created = existing == null;
			Asset asset;
			if (created) {
				AssetCreateRequest request = new AssetCreateRequest();
				request.setFile(new FileInfo()
					.setFilename(filename)
					.setMimeType(mimeType)
					.setOrigin(origin)
					.setSize(size)
					.setFirstSeen(Instant.now()));
				request.setHashes(new HashInfo().setSHA512(sha512));
				asset = assetService.createAsset(userUuid, request);
			} else {
				asset = existing;
			}

			// Record where the bytes live so the asset-scoped pipeline run can locate the file on the
			// worker. One row per library: re-uploading into the same library updates rather than
			// duplicating, which the (library_uuid, path) unique index would reject anyway.
			AssetBinary binary = daos.assetBinaryDao().loadByAssetAndLibrary(asset.getUuid(), libraryUuid);
			if (binary == null) {
				binary = daos.assetBinaryDao().createAssetBinary(locator, asset.getUuid(), userUuid, libraryUuid);
				binary.setMimeType(mimeType);
				binary.setPoolUuid(poolUuid);
				daos.assetBinaryDao().store(binary);
			} else if (!locator.equals(binary.getPath())) {
				String previousLocator = binary.getPath();
				UUID previousPool = binary.getPoolUuid();
				binary.setPath(locator);
				binary.setMimeType(mimeType);
				binary.setPoolUuid(poolUuid);
				setEditor(binary, userUuid);
				daos.assetBinaryDao().update(binary);
				BinaryReclaimer.reclaim(daos, storageResolver, previousPool, previousLocator);
			}

			if (created) {
				// Let a matching pipeline pick this up. Published after the binary row exists so the
				// consumer can resolve the locator. Not published for content Loom already holds: the
				// asset was processed when it first arrived, and re-running on every duplicate upload
				// would be pure cost.
				eventPublisher.publishCreated(asset.getUuid(), mimeType);
			}

			log.info("{} asset {} ({} bytes, {}) stored at {} [{}]", created ? "Uploaded" : "Reused existing", asset.getUuid(), size, mimeType,
				locator, storage.describe());
			lrc.send(modelBuilder.toResponse(asset), created ? 201 : 200);
		}, required);
	}

	/**
	 * Upload raw file bytes for an already existing asset. Expects exactly one file part.
	 *
	 * <p>
	 * An asset may hold a location per library, so which row this replaces is decided by the {@code libraryUuid} form field. It may be omitted only
	 * when the answer is unambiguous: the asset has exactly one location (replace it) or none at all is not allowed, since a new row needs a library.
	 * Omitting it while several locations exist is a 400 rather than a guess — picking one would silently overwrite a different library's binary.
	 * </p>
	 */
	public void uploadForAsset(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, CREATE_ASSET_BINARY, () -> {
			FileUpload upload = singleUpload(lrc);
			UUID userUuid = lrc.userUuid();
			String mimeType = upload.contentType();

			AssetBinary existing = resolveTarget(lrc, assetUuid);
			UUID libraryUuid = existing != null ? existing.getLibraryUuid() : requiredLibraryUuid(lrc);
			UUID poolUuid = storageResolver.poolUuidOfLibrary(libraryUuid);
			BinaryStorage storage = storageResolver.forPool(poolUuid);
			capacityGuard.checkUpload(storage, upload.size());

			SHA512 sha512 = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));
			String locator = storage.store(Paths.get(upload.uploadedFileName()), sha512, mimeType);

			AssetBinary binary;
			if (existing != null) {
				String previousLocator = existing.getPath();
				UUID previousPool = existing.getPoolUuid();
				existing.setPath(locator);
				existing.setMimeType(mimeType);
				existing.setPoolUuid(poolUuid);
				setEditor(existing, userUuid);
				binary = daos.assetBinaryDao().update(existing);
				// The row no longer points at the old bytes. Reclaim them if nothing else does.
				BinaryReclaimer.reclaim(daos, storageResolver, previousPool, previousLocator);
			} else {
				binary = daos.assetBinaryDao().createAssetBinary(locator, assetUuid, userUuid, libraryUuid);
				binary.setMimeType(mimeType);
				binary.setPoolUuid(poolUuid);
				daos.assetBinaryDao().store(binary);
			}

			log.info("Stored binary for asset {} ({}) at {} [{}]", assetUuid, mimeType, locator, storage.describe());
			lrc.send(modelBuilder.toResponse(binary), 201);
		});
	}

	/**
	 * Which existing location this upload replaces, or null when a new one should be created.
	 */
	private AssetBinary resolveTarget(LoomRoutingContext lrc, UUID assetUuid) {
		String libraryValue = lrc.routingContext().request().getFormAttribute("libraryUuid");
		if (libraryValue != null && !libraryValue.isBlank()) {
			return daos.assetBinaryDao().loadByAssetAndLibrary(assetUuid, parseUuid(libraryValue, "libraryUuid"));
		}
		var existing = daos.assetBinaryDao().loadAllByAssetUuid(assetUuid);
		if (existing.isEmpty()) {
			return null;
		}
		if (existing.size() > 1) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"This asset has " + existing.size() + " binaries. Name the target library with the 'libraryUuid' form field.");
		}
		return existing.get(0);
	}

	private UUID requiredLibraryUuid(LoomRoutingContext lrc) {
		return requiredUuid(lrc, "libraryUuid");
	}
}
