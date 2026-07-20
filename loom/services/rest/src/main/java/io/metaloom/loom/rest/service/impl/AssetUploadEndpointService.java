package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.file.FileSystem;
import io.vertx.ext.web.FileUpload;

/**
 * Handles multipart uploads that create an asset from real file bytes.
 *
 * <p>
 * The uploaded bytes are persisted into the configured storage directory using a sha512-segmented layout ({@code ab/cd/ef/<sha512>}), an asset is
 * created with the derived file metadata, a binary location row is recorded pointing at the stored file, and an {@code asset.created} event is
 * published so a matching pipeline can be triggered.
 * </p>
 */
@Singleton
public class AssetUploadEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(AssetUploadEndpointService.class);

	private static final String DEFAULT_ORIGIN = "upload";

	private final AssetEndpointService assetService;
	private final DaoCollection daos;
	private final LoomOptions options;
	private final FileSystem fileSystem;
	private final AssetEventPublisher eventPublisher;

	@Inject
	public AssetUploadEndpointService(AssetEndpointService assetService, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, LoomOptions options, FileSystem fileSystem, AssetEventPublisher eventPublisher) {
		super(modelBuilder, validator);
		this.assetService = assetService;
		this.daos = daos;
		this.options = options;
		this.fileSystem = fileSystem;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Create an asset from a multipart file upload. Expects exactly one file part and a {@code libraryUuid} form field. Optional form fields:
	 * {@code origin} (defaults to {@code upload}).
	 */
	public void upload(LoomRoutingContext lrc) {
		checkPerm(lrc, CREATE_ASSET, () -> {
			if (lrc.fileUploads().isEmpty()) {
				throw new LoomRestException(400, LoomRestErrorCode.UPLOAD_DATA_MISSING, "No uploads found in request.");
			}
			if (lrc.fileUploads().size() > 1) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"Upload with multiple files in one request is currently not supported");
			}

			FileUpload upload = lrc.fileUploads().get(0);
			UUID userUuid = lrc.userUuid();
			UUID libraryUuid = requiredLibraryUuid(lrc);
			String origin = formValue(lrc, "origin", DEFAULT_ORIGIN);

			String filename = upload.fileName();
			long size = upload.size();
			String mimeType = upload.contentType();
			SHA512 sha512 = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));

			// Persist the bytes into the storage directory before recording anything in the DB, so a
			// failure to store never leaves a dangling asset that points at a missing file.
			String storedPath = persist(upload.uploadedFileName(), sha512);

			AssetCreateRequest request = new AssetCreateRequest();
			request.setFile(new FileInfo()
				.setFilename(filename)
				.setMimeType(mimeType)
				.setOrigin(origin)
				.setSize(size)
				.setFirstSeen(Instant.now()));
			request.setHashes(new HashInfo().setSHA512(sha512));

			Asset asset = assetService.createAsset(userUuid, request);

			// Record where the bytes live so the asset-scoped pipeline run can locate the file on the worker.
			AssetBinary binary = daos.assetBinaryDao().createAssetBinary(storedPath, asset.getUuid(), userUuid, libraryUuid);
			binary.setMimeType(mimeType);
			daos.assetBinaryDao().store(binary);

			// Let a matching pipeline pick this up. Published after the binary row exists so the
			// consumer can resolve the on-disk path.
			eventPublisher.publishCreated(asset.getUuid(), mimeType);

			log.info("Uploaded asset {} ({} bytes, {}) stored at {}", asset.getUuid(), size, mimeType, storedPath);
			lrc.send(modelBuilder.toResponse(asset), 201);
		});
	}

	/**
	 * Persist the uploaded temp file into the configured storage directory using an {@code ab/cd/ef/<sha512>} layout. Existing content for the same
	 * sha512 is treated as already stored (content-addressed dedupe).
	 *
	 * @return the absolute-ish stored path (relative to the process working dir when the upload dir is relative)
	 */
	private String persist(String tempFilePath, SHA512 sha512) {
		String hex = sha512.toString();
		String baseDir = options.getStorage().getUploadDirectory();
		String targetDir = Paths.get(baseDir, hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6)).toString();
		String targetPath = Paths.get(targetDir, hex).toString();

		fileSystem.mkdirsBlocking(targetDir);
		if (fileSystem.existsBlocking(targetPath)) {
			log.debug("Binary for {} already present, reusing existing file", hex);
			return targetPath;
		}
		// copy (rather than move) so a cross-device temp dir does not fail; Vert.x cleans the temp file after the request.
		fileSystem.copyBlocking(tempFilePath, targetPath);
		return targetPath;
	}

	private UUID requiredLibraryUuid(LoomRoutingContext lrc) {
		String value = lrc.routingContext().request().getFormAttribute("libraryUuid");
		if (value == null || value.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The 'libraryUuid' form field is required for uploads.");
		}
		try {
			return UUID.fromString(value.trim());
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The 'libraryUuid' form field is not a valid UUID.");
		}
	}

	private String formValue(LoomRoutingContext lrc, String field, String defaultValue) {
		String value = lrc.routingContext().request().getFormAttribute(field);
		return (value == null || value.isBlank()) ? defaultValue : value.trim();
	}
}
