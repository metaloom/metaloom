package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.READ_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ATTACHMENT;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.attachment.AttachmentUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.FileUpload;

/**
 * Attachments: derived binaries hanging off an asset or an embedding — contact sheets, poster frames, waveforms, proxies, extracted audio.
 *
 * <p>
 * Until this was wired up, {@code create} hashed the uploaded part, wrote a row and let Vert.x delete the temp file, so every attachment referenced
 * content that existed nowhere and there was no route to read one back. The bytes now go to the same {@link BinaryStorage} an asset binary would use,
 * and {@code GET /attachments/:uuid/data} serves them.
 * </p>
 *
 * <p>
 * Which pool: the one holding the parent asset's primary binary, so an attachment does not land on local disk when the asset it describes lives in a
 * bucket. Failing that (an embedding attachment, or an asset with no binary), the default local storage.
 * </p>
 */
public class AttachmentEndpointService extends AbstractCRUDEndpointService<AttachmentDao, Attachment> {

	private static final Logger log = LoggerFactory.getLogger(AttachmentEndpointService.class);

	private final BinaryStorageResolver storageResolver;

	@Inject
	public AttachmentEndpointService(AttachmentDao attachmentDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		BinaryStorageResolver storageResolver) {
		super(attachmentDao, daos, modelBuilder, validator);
		this.storageResolver = storageResolver;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ATTACHMENT, uuid);
		// Note: the bytes are deliberately not reclaimed here. attachment_binary is a shared,
		// content-addressed row that outlives any single attachment (that is why it is a separate
		// table keyed by sha512sum), and there is no cross-table reference count covering both it
		// and asset_location. Reclaiming attachment bytes is tracked in REST_BINARY_HANDLING.md.
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ATTACHMENT, modelBuilder::toAttachmentList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ATTACHMENT, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ATTACHMENT, () -> {
			FileUpload upload = singleUpload(lrc);
			UUID userUuid = lrc.userUuid();
			String filename = upload.fileName();
			long size = upload.size();
			String mimeType = upload.contentType();
			AttachmentType type = attachmentType(lrc);
			UUID assetUuid = optionalUuid(lrc, "assetUuid");
			UUID embeddingUuid = optionalUuid(lrc, "embeddingUuid");
			// A face crop belongs to one detected face rather than to the whole asset - an asset has many
			// faces, so the asset pointer alone cannot address a crop.
			UUID detectionUuid = optionalUuid(lrc, "detectionUuid");
			String variant = lrc.routingContext().request().getFormAttribute("variant");
			String nodeKind = lrc.routingContext().request().getFormAttribute("nodeKind");

			SHA512 sha512sum = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));
			UUID poolUuid = poolFor(lrc, assetUuid);
			BinaryStorage storage = storageResolver.forPool(poolUuid);

			// Store before the row exists, so an attachment never points at content that is not there.
			storage.store(Paths.get(upload.uploadedFileName()), sha512sum, mimeType);

			Attachment attachment = dao().createAttachment(userUuid, sha512sum, filename, size, mimeType, type);
			attachment.setPoolUuid(poolUuid);
			attachment.setAssetUuid(assetUuid);
			attachment.setEmbeddingUuid(embeddingUuid);
			attachment.setDetectionUuid(detectionUuid);
			attachment.setVariant(variant == null ? "" : variant);
			attachment.setNodeKind(nodeKind);
			log.info("Stored attachment {} ({} bytes, {}) in {}", filename, size, mimeType, storage.describe());
			return attachment;
		}, modelBuilder::toResponse);
	}

	/**
	 * Stream an attachment's bytes.
	 *
	 * <p>
	 * The locator is derived from the sha512sum rather than read from a column: {@code attachment_binary} records the pool and the hash and nothing
	 * else, and the storage layout is content-addressed.
	 * </p>
	 */
	public void download(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, READ_ATTACHMENT, () -> {
			Attachment attachment = dao().load(uuid);
			if (attachment == null || attachment.getSha512sum() == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Attachment not found.");
			}
			BinaryStorage storage = storageResolver.forPool(attachment.getPoolUuid());
			String locator = storage.locatorFor(attachment.getSha512sum());
			if (!storage.exists(locator)) {
				// Pre-existing rows are the expected case here: attachments created before the bytes were
				// stored reference content that was never written anywhere.
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"The attachment's bytes are missing in " + storage.describe() + ".");
			}

			String mimeType = attachment.getMimeType() != null ? attachment.getMimeType() : "application/octet-stream";
			String fileName = attachment.getFilename() != null ? attachment.getFilename() : uuid.toString();

			HttpServerResponse response = lrc.routingContext().response();
			response.putHeader(HttpHeaders.CONTENT_TYPE, mimeType);
			response.putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

			Optional<Path> local = storage.localPath(locator);
			if (local.isPresent()) {
				response.sendFile(local.get().toString());
				return;
			}
			long size = storage.size(locator);
			if (size >= 0) {
				response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
			} else {
				// Vert.x refuses a write with neither a Content-Length nor chunked encoding.
				response.setChunked(true);
			}
			try (InputStream in = storage.read(locator, 0, -1)) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = in.read(buffer)) > 0) {
					response.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOf(buffer, read)));
				}
				response.end();
			} catch (Exception e) {
				log.error("Failed to stream attachment {} from {}", uuid, storage.describe(), e);
				if (!response.headWritten()) {
					throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the attachment.");
				}
				response.reset();
			}
		});
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_ATTACHMENT, () -> {
			AttachmentUpdateRequest request = lrc.requestBody(AttachmentUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Attachment attachment = dao().load(id);
			update(request::getFilename, attachment::setFilename);
			update(request::getMimeType, attachment::setMimeType);
			update(request::getMeta, attachment::setMeta);
			setEditor(attachment, userUuid);
			return attachment;
		}, modelBuilder::toResponse);
	}

	/**
	 * Put an attachment next to the asset it describes.
	 */
	private UUID poolFor(LoomRoutingContext lrc, UUID assetUuid) {
		UUID explicit = optionalUuid(lrc, "poolUuid");
		if (explicit != null) {
			return explicit;
		}
		if (assetUuid == null) {
			return null;
		}
		AssetBinary binary = daos().assetBinaryDao().loadPrimaryByAssetUuid(assetUuid);
		return binary == null ? null : binary.getPoolUuid();
	}

	private AttachmentType attachmentType(LoomRoutingContext lrc) {
		String value = lrc.routingContext().request().getFormAttribute("type");
		if (value == null || value.isBlank()) {
			// Historic default. Kept so existing callers, which send no type at all, behave as before.
			return AttachmentType.EMBEDDING_ATTACHMENT;
		}
		try {
			return AttachmentType.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Unknown attachment type '" + value + "'.");
		}
	}

	private UUID optionalUuid(LoomRoutingContext lrc, String field) {
		String value = lrc.routingContext().request().getFormAttribute(field);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value.trim());
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' form field is not a valid UUID.");
		}
	}

	private FileUpload singleUpload(LoomRoutingContext lrc) {
		if (lrc.fileUploads().isEmpty()) {
			throw new LoomRestException(400, LoomRestErrorCode.UPLOAD_DATA_MISSING, "No uploads found in request.");
		}
		if (lrc.fileUploads().size() > 1) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"Upload with multiple files in one request is currently not supported");
		}
		return lrc.fileUploads().get(0);
	}
}
