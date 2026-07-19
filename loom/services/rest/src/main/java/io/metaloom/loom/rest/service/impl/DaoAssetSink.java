package io.metaloom.loom.rest.service.impl;

import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.pipeline.engine.AssetSink;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

/**
 * Persists node output onto the asset it was computed from.
 *
 * <p>This is what {@code syncToLoom} has always promised and never done. Before
 * this, a node's output was recorded against the run in
 * {@code pipeline_node_task.outputs} and then went nowhere - computed, stored, and
 * invisible everywhere an asset is actually looked at.</p>
 *
 * <h2>Why the sha512 is the anchor</h2>
 *
 * <p>Assets are keyed by content hash ({@code AssetDao.loadBySHA512}), so nothing
 * can be written until something has produced one. A pipeline that computes a
 * thumbnail but no hash therefore has nowhere to put it, and this says so rather
 * than inventing an identity for the file.</p>
 */
public class DaoAssetSink implements AssetSink {

	private static final Logger log = LoggerFactory.getLogger(DaoAssetSink.class);

	/** Output keys this sink knows how to map onto an asset. */
	private static final String OUT_SHA512 = "sha512";
	private static final String OUT_SHA256 = "sha256";
	private static final String OUT_MD5 = "md5";

	private final AssetDao assetDao;
	private final UUID userUuid;

	public DaoAssetSink(AssetDao assetDao, UUID userUuid) {
		this.assetDao = assetDao;
		this.userUuid = userUuid;
	}

	@Override
	public synchronized void persist(MediaRef media, String nodeId, Map<String, Object> outputs) {
		String sha512 = stringOutput(outputs, OUT_SHA512);
		if (sha512 == null) {
			// Nothing to key on. Not an error: a node can legitimately produce output
			// before anything has hashed the file, and the run records it either way.
			log.debug("Node '{}' produced no sha512 for {}; nothing to anchor an asset on", nodeId, media.getPath());
			return;
		}

		Asset asset = assetDao.loadBySHA512(SHA512.fromString(sha512));
		boolean created = false;
		if (asset == null) {
			asset = assetDao.createAsset(userUuid, SHA512.fromString(sha512), mimeTypeOf(media), filenameOf(media),
				"pipeline", Math.max(0, media.getSize()));
			created = true;
		}

		applyHashes(asset, outputs);
		if (created) {
			assetDao.store(asset);
		} else {
			assetDao.update(asset);
		}

		if (log.isDebugEnabled()) {
			log.debug("{} asset for {} from node '{}'", created ? "Created" : "Updated", media.getPath(), nodeId);
		}
		warnAboutUnmapped(nodeId, outputs);
	}

	private void applyHashes(Asset asset, Map<String, Object> outputs) {
		String sha256 = stringOutput(outputs, OUT_SHA256);
		if (sha256 != null) {
			asset.setSHA256(SHA256.fromString(sha256));
		}
		String md5 = stringOutput(outputs, OUT_MD5);
		if (md5 != null) {
			asset.setMD5(MD5.fromString(md5));
		}
	}

	/**
	 * Say plainly what was dropped.
	 *
	 * <p>Only hashes have somewhere to go today. Thumbnails, embeddings, OCR text and
	 * transcripts are computed and still land nowhere, and this is the seam where
	 * mapping them belongs - logging it keeps that gap visible instead of letting the
	 * data disappear silently, which is how it went unnoticed until now.</p>
	 */
	private void warnAboutUnmapped(String nodeId, Map<String, Object> outputs) {
		for (String key : outputs.keySet()) {
			if (!OUT_SHA512.equals(key) && !OUT_SHA256.equals(key) && !OUT_MD5.equals(key)) {
				log.info("Node '{}' output '{}' has no asset mapping and was not persisted", nodeId, key);
			}
		}
	}

	/**
	 * Best-effort mime type, from the filename extension alone.
	 *
	 * <p>{@code asset.mime_type} is NOT NULL, so something has to be supplied. It cannot
	 * be sniffed from the content: the file lives on the worker that scanned it, and
	 * Loom has no access to it - only the path and the hashes ever cross the wire.
	 * {@link MediaRef} carries no mime type either, so the extension is all there is.</p>
	 *
	 * <p>A wrong guess is preferable to a failed insert, which is what happened before:
	 * passing null aborted the whole write and the hash reached no asset at all. When
	 * the extension is unknown the generic type is used rather than a guess.</p>
	 */
	private static String mimeTypeOf(MediaRef media) {
		String filename = filenameOf(media).toLowerCase();
		int dot = filename.lastIndexOf('.');
		String extension = dot < 0 ? "" : filename.substring(dot + 1);
		return switch (extension) {
			case "mp4", "m4v" -> "video/mp4";
			case "mov" -> "video/quicktime";
			case "mkv" -> "video/x-matroska";
			case "webm" -> "video/webm";
			case "avi" -> "video/x-msvideo";
			case "jpg", "jpeg" -> "image/jpeg";
			case "png" -> "image/png";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "tif", "tiff" -> "image/tiff";
			case "mp3" -> "audio/mpeg";
			case "wav" -> "audio/wav";
			case "flac" -> "audio/flac";
			case "ogg" -> "audio/ogg";
			case "pdf" -> "application/pdf";
			case "txt" -> "text/plain";
			default -> "application/octet-stream";
		};
	}

	private static String filenameOf(MediaRef media) {
		try {
			return Paths.get(media.getPath()).getFileName().toString();
		} catch (Exception e) {
			return media.getPath();
		}
	}

	private static String stringOutput(Map<String, Object> outputs, String key) {
		Object value = outputs.get(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

}
