package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.CREATE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ATTACHMENT;
import static io.metaloom.loom.db.model.perm.Permission.READ_ATTACHMENT;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.ChatAttachmentOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.FileUpload;

/**
 * Files dropped into a chat: upload, list, download, remove, and promote into the library.
 *
 * <h2>Why these are not assets</h2>
 *
 * <p>
 * A file handed to the agent mid-conversation is not catalogued material. Filing it as an asset
 * would run whatever ingest pipelines match it, thumbnail it, index it for search, and make "do we
 * already have this picture?" answer yes because the user had just dropped it. So it is a
 * {@code CHAT_FILE} row on {@code attachment} — the table V2.92 describes as "the sink for binaries
 * that are not assets" — which dies with the chat (V2.113). {@link #promote} is the deliberate way
 * out: it copies the bytes into a real asset, which then has its own life.
 * </p>
 *
 * <h2>Ownership, on every route</h2>
 *
 * <p>
 * {@code CREATE_ATTACHMENT} and friends say what a caller may do with attachments; they cannot say
 * <em>whose</em> chat. Every method here therefore runs {@link ChatOwnership#loadOwned} inside its
 * permission check, and a chat belonging to somebody else is a 404. The generic {@code /attachments}
 * routes carry the mirror image of this guard so chat files cannot be reached around the side.
 * </p>
 */
public class ChatAttachmentEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(ChatAttachmentEndpointService.class);

	/** Provenance stamped on an asset promoted out of a chat, so its origin is legible later. */
	public static final String PROMOTED_ORIGIN = "chat:attachment";

	private final DaoCollection daos;
	private final BinaryStorageResolver storageResolver;
	private final StorageCapacityGuard capacityGuard;
	private final ChatOwnership chatOwnership;
	private final ProducedAssetIngestor ingestor;
	private final ChatAttachmentOptions options;

	@Inject
	public ChatAttachmentEndpointService(DaoCollection daos, LoomModelBuilder modelBuilder, BinaryStorageResolver storageResolver,
		StorageCapacityGuard capacityGuard, ChatOwnership chatOwnership, ProducedAssetIngestor ingestor, LoomOptions loomOptions,
		LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.daos = daos;
		this.storageResolver = storageResolver;
		this.capacityGuard = capacityGuard;
		this.chatOwnership = chatOwnership;
		this.ingestor = ingestor;
		this.options = loomOptions.getChatAttachment();
	}

	/**
	 * {@code POST /api/v1/chats/:uuid/attachments} — attach a file to a conversation.
	 */
	public void create(LoomRoutingContext lrc, UUID chatUuid) {
		checkPerm(lrc, CREATE_ATTACHMENT, () -> {
			requireEnabled();
			chatOwnership.loadOwned(chatUuid, lrc.userUuid());

			FileUpload upload = singleUpload(lrc);
			long size = upload.size();
			if (size > options.getMaxBytes()) {
				throw new LoomRestException(413, LoomRestErrorCode.BAD_REQUEST,
					"That file is " + size + " bytes; the limit for a chat attachment is " + options.getMaxBytes() + ".");
			}

			List<Attachment> existing = daos.attachmentDao().listByChat(chatUuid);
			if (existing.size() >= options.getMaxFiles()) {
				// A cap rather than a queue: the manifest is rebuilt into the system prompt on every
				// turn, so silently dropping the oldest would change what the agent can see mid-chat.
				throw new LoomRestException(409, LoomRestErrorCode.BAD_REQUEST,
					"This conversation already has " + existing.size() + " attachments, which is the limit. Remove one first.");
			}

			String filename = upload.fileName();
			String mimeType = upload.contentType();
			SHA512 sha512sum = HashUtils.computeSHA512(Paths.get(upload.uploadedFileName()));

			// No parent asset to inherit a pool from, so the deployment default unless one is named -
			// the same answer a user avatar gets, and for the same reason.
			UUID poolUuid = optionalUuid(lrc, "poolUuid");
			BinaryStorage storage = storageResolver.forPool(poolUuid);
			capacityGuard.checkUpload(storage, size);

			// Store before the row exists, so an attachment never points at content that is not there.
			storage.store(Paths.get(upload.uploadedFileName()), sha512sum, mimeType);

			Attachment attachment = daos.attachmentDao().createAttachment(lrc.userUuid(), sha512sum, filename, size, mimeType,
				AttachmentType.CHAT_FILE);
			attachment.setChatUuid(chatUuid);
			attachment.setPoolUuid(poolUuid);
			// Not derived from anything, so both discriminators stay empty - see AttachmentType.CHAT_FILE.
			attachment.setVariant("");
			daos.attachmentDao().store(attachment);

			log.info("Attached {} ({} bytes, {}) to chat {} in {}", filename, size, mimeType, chatUuid, storage.describe());
			lrc.send(modelBuilder.toResponse(attachment), 201);
		});
	}

	/**
	 * {@code GET /api/v1/chats/:uuid/attachments} — the conversation's files, newest first.
	 */
	public void list(LoomRoutingContext lrc, UUID chatUuid) {
		checkPerm(lrc, READ_ATTACHMENT, () -> {
			chatOwnership.loadOwned(chatUuid, lrc.userUuid());
			lrc.send(modelBuilder.toAttachmentList(daos.attachmentDao().listByChat(chatUuid)));
		});
	}

	/**
	 * {@code GET /api/v1/chats/:uuid/attachments/:attachmentUuid/data} — the bytes.
	 *
	 * <p>
	 * The chat UI renders a dropped picture from this route through a blob URL, not by pointing an
	 * {@code <img src>} at it — the same thing {@code useAuthedImage} does for asset binaries. The
	 * {@code ?mt=} media token is deliberately mounted on the two asset routes only and is scoped to
	 * an asset uuid, so it does not apply here and must not be widened to cover this.
	 * </p>
	 */
	public void download(LoomRoutingContext lrc, UUID chatUuid, UUID attachmentUuid) {
		checkPerm(lrc, READ_ATTACHMENT, () -> {
			chatOwnership.loadOwned(chatUuid, lrc.userUuid());
			Attachment attachment = requireChatFile(chatUuid, attachmentUuid);

			BinaryStorage storage = storageResolver.forPool(attachment.getPoolUuid());
			String locator = storage.locatorFor(attachment.getSha512sum());
			if (!storage.exists(locator)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"The attachment's bytes are missing in " + storage.describe() + ".");
			}
			sendBytes(lrc, storage, locator, attachment);
		});
	}

	/**
	 * {@code DELETE /api/v1/chats/:uuid/attachments/:attachmentUuid} — detach a file.
	 *
	 * <p>
	 * The row goes; the bytes stay. {@code attachment_binary} is content-addressed and shared, so
	 * reclaiming them needs a reference count that spans it and {@code asset_location} — the same
	 * reason the generic delete leaves them, tracked in REST_BINARY_HANDLING.md.
	 * </p>
	 */
	public void delete(LoomRoutingContext lrc, UUID chatUuid, UUID attachmentUuid) {
		checkPerm(lrc, DELETE_ATTACHMENT, () -> {
			chatOwnership.loadOwned(chatUuid, lrc.userUuid());
			requireChatFile(chatUuid, attachmentUuid);
			daos.attachmentDao().delete(attachmentUuid);
			lrc.sendNoContent();
		});
	}

	/**
	 * {@code POST /api/v1/chats/:uuid/attachments/:attachmentUuid/asset} — keep this one.
	 *
	 * <p>
	 * The deliberate way a conversational file becomes catalogued material. It runs the ordinary
	 * ingest, so the new asset is hashed, deduplicated, published and picked up by matching
	 * pipelines like any upload — which is exactly what was <em>not</em> wanted when the file was
	 * merely dropped into a chat.
	 * </p>
	 *
	 * <p>
	 * The attachment stays where it is. A file can be in the conversation and in the library at once,
	 * and deleting the chat afterwards must not take the asset with it.
	 * </p>
	 */
	public void promote(LoomRoutingContext lrc, UUID chatUuid, UUID attachmentUuid) {
		checkPerms(lrc, () -> {
			chatOwnership.loadOwned(chatUuid, lrc.userUuid());
			Attachment attachment = requireChatFile(chatUuid, attachmentUuid);

			UUID libraryUuid = optionalUuid(firstQueryParam(lrc, "libraryUuid"), "libraryUuid");
			if (libraryUuid == null) {
				libraryUuid = configuredLibrary();
			}
			if (libraryUuid == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"Name the library to save this into with the 'libraryUuid' query parameter, or configure "
						+ "LOOM_CHAT_ATTACHMENT_LIBRARY as the default.");
			}

			BinaryStorage storage = storageResolver.forPool(attachment.getPoolUuid());
			String locator = storage.locatorFor(attachment.getSha512sum());
			if (!storage.exists(locator)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
					"The attachment's bytes are missing in " + storage.describe() + ".");
			}

			try (InputStream in = storage.read(locator, 0, -1)) {
				// Read into memory rather than streamed: the ingest hashes and stores from a path, and
				// a chat attachment is bounded by LOOM_CHAT_ATTACHMENT_MAX_BYTES, which is small.
				Asset asset = ingestor.ingest(lrc.userUuid(), libraryUuid, in.readAllBytes(), attachment.getFilename(),
					attachment.getMimeType(), PROMOTED_ORIGIN);
				log.info("Promoted chat attachment {} of chat {} into asset {}", attachmentUuid, chatUuid, asset.getUuid());
				lrc.send(modelBuilder.toResponse(asset), 201);
			} catch (LoomRestException e) {
				throw e;
			} catch (Exception e) {
				log.error("Could not promote attachment {} of chat {}", attachmentUuid, chatUuid, e);
				throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not save the attachment into the library.");
			}
		}, CREATE_ASSET, READ_ATTACHMENT);
	}

	/**
	 * The attachment, insisting it is a chat file of <em>this</em> chat.
	 *
	 * <p>
	 * The chat is already known to be the caller's when this runs, so the check that matters here is
	 * the second one: an attachment uuid from another conversation must not be readable by quoting it
	 * against a chat the caller does happen to own.
	 * </p>
	 */
	private Attachment requireChatFile(UUID chatUuid, UUID attachmentUuid) {
		Attachment attachment = daos.attachmentDao().load(attachmentUuid);
		if (attachment == null || attachment.getType() != AttachmentType.CHAT_FILE || !chatUuid.equals(attachment.getChatUuid())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Attachment not found.");
		}
		return attachment;
	}

	private static String firstQueryParam(LoomRoutingContext lrc, String key) {
		List<String> values = lrc.queryParam(key);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private void requireEnabled() {
		if (!options.isEnabled()) {
			throw new LoomRestException(403, LoomRestErrorCode.BAD_REQUEST,
				"Chat attachments are disabled in this deployment (LOOM_CHAT_ATTACHMENT_ENABLED).");
		}
	}

	private UUID configuredLibrary() {
		String configured = options.getLibraryUuid();
		if (configured == null || configured.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(configured.trim());
		} catch (IllegalArgumentException e) {
			log.warn("LOOM_CHAT_ATTACHMENT_LIBRARY is not a valid uuid: {}", configured);
			return null;
		}
	}

	/** Stream bytes out, preferring a local file so Vert.x can sendfile it. Mirrors AttachmentEndpointService. */
	private void sendBytes(LoomRoutingContext lrc, BinaryStorage storage, String locator, Attachment attachment) {
		String mimeType = attachment.getMimeType() != null ? attachment.getMimeType() : "application/octet-stream";
		String fileName = attachment.getFilename() != null ? attachment.getFilename() : attachment.getUuid().toString();

		HttpServerResponse response = lrc.routingContext().response();
		response.putHeader(HttpHeaders.CONTENT_TYPE, mimeType);
		// inline, not attachment: the chat renders a dropped picture in place rather than downloading it.
		response.putHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");

		Optional<Path> local = storage.localPath(locator);
		if (local.isPresent()) {
			response.sendFile(local.get().toString());
			return;
		}
		long size = storage.size(locator);
		if (size >= 0) {
			response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
		} else {
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
			log.error("Failed to stream chat attachment {} from {}", attachment.getUuid(), storage.describe(), e);
			if (!response.headWritten()) {
				throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the attachment.");
			}
			response.reset();
		}
	}

}
