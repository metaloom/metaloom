package io.metaloom.loom.rest.service.impl;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.storage.BinaryStorage;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;

/**
 * Streams the bytes of a picture attachment back to the caller.
 *
 * <p>
 * Shared by the person-image and user-avatar download routes, which need byte-identical behaviour: both serve a small image that the browser will ask
 * for again on every screen that renders it, both are addressed by content hash, and both depict an identified human.
 * </p>
 *
 * <p>
 * Not used by {@code GET /assets/:uuid/binary/data}. That route serves multi-gigabyte media and implements {@code Range} (206/416); this one
 * deliberately does not, because a thumbnail-sized response has nothing to seek within.
 * </p>
 */
@Singleton
public class AttachmentBinarySender {

	private static final Logger log = LoggerFactory.getLogger(AttachmentBinarySender.class);

	private static final String DEFAULT_MIME_TYPE = "image/jpeg";

	private static final int BUFFER_SIZE = 64 * 1024;

	private final BinaryStorageResolver storageResolver;

	@Inject
	public AttachmentBinarySender(BinaryStorageResolver storageResolver) {
		this.storageResolver = storageResolver;
	}

	/**
	 * Write an attachment's bytes to the response, honouring {@code If-None-Match}.
	 *
	 * @param lrc         the request being answered
	 * @param attachment  the row whose bytes to serve; must carry a sha512sum
	 * @param description what to call this in a 404 and in the error log, e.g. {@code "image"} or {@code "avatar"}
	 */
	public void send(LoomRoutingContext lrc, Attachment attachment, String description) {
		BinaryStorage storage = storageResolver.forPool(attachment.getPoolUuid());
		String locator = storage.locatorFor(attachment.getSha512sum());
		if (!storage.exists(locator)) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
				"The " + description + "'s bytes are missing in " + storage.describe() + ".");
		}

		HttpServerResponse response = lrc.routingContext().response();
		// The row is immutable once written and the bytes are addressed by their own hash, so this is safe to cache hard.
		String etag = "\"" + attachment.getUuid() + "-" + attachment.getSha512sum().toString().substring(0, 16) + "\"";
		if (etag.equals(lrc.routingContext().request().getHeader(HttpHeaders.IF_NONE_MATCH))) {
			response.setStatusCode(304).end();
			return;
		}
		response.putHeader(HttpHeaders.CONTENT_TYPE, attachment.getMimeType() == null ? DEFAULT_MIME_TYPE : attachment.getMimeType());
		response.putHeader(HttpHeaders.ETAG, etag);
		// private: a picture of an identified person is not something a shared cache should hold.
		response.putHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable");

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
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) > 0) {
				response.write(Buffer.buffer(Arrays.copyOf(buffer, read)));
			}
			response.end();
		} catch (Exception e) {
			log.error("Failed to stream {} {}", description, attachment.getUuid(), e);
			if (!response.headWritten()) {
				throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the " + description + ".");
			}
		}
	}
}
