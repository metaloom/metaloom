package io.metaloom.loom.rest.service.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.storage.BinaryStorage;

/**
 * The one place that decides whether an upload is allowed to consume storage, and how healthy a backend is.
 *
 * <p>
 * It exists because the check used to live privately inside {@code AssetUploadEndpointService}, which meant only asset uploads were guarded. Every
 * other route that writes bytes — attachments, person images, user avatars — could fill the volume unchecked, and each of them then failed with an
 * {@code IOException} surfacing as a 500 rather than the 507 that says what is actually wrong.
 * </p>
 *
 * <p>
 * The router deliberately sets {@code BodyHandler.setBodyLimit(-1)} because media is large, so this is the only thing standing between an
 * authenticated caller and a full disk.
 * </p>
 */
@Singleton
public class StorageCapacityGuard {

	/** Rejection reason label for {@code loom_storage_upload_rejections_total}. */
	public static final String REASON_TOO_LARGE = "too_large";

	/** Rejection reason label for {@code loom_storage_upload_rejections_total}. */
	public static final String REASON_NO_SPACE = "no_space";

	private final LoomOptions options;

	private final LoomMetrics metrics;

	@Inject
	public StorageCapacityGuard(LoomOptions options, LoomMetrics metrics) {
		this.options = options;
		this.metrics = metrics;
	}

	/**
	 * Reject an upload that is too large, or that the target backend cannot hold.
	 *
	 * @param storage the backend the bytes are about to be written to
	 * @param size    the size of the upload in bytes
	 * @throws LoomRestException 413 when the upload exceeds {@code LOOM_STORAGE_MAX_UPLOAD_SIZE}, 507 when storing it would take the backend below
	 *                           {@code LOOM_STORAGE_MIN_FREE_SPACE}
	 */
	public void checkUpload(BinaryStorage storage, long size) {
		long maxUploadSize = options.getStorage().getMaxUploadSize();
		if (maxUploadSize > 0 && size > maxUploadSize) {
			metrics.recordUploadRejected(REASON_TOO_LARGE);
			throw new LoomRestException(413, LoomRestErrorCode.BAD_REQUEST,
				"The upload is " + size + " bytes which exceeds the configured limit of " + maxUploadSize
					+ " bytes (LOOM_STORAGE_MAX_UPLOAD_SIZE).");
		}
		long minFreeSpace = options.getStorage().getMinFreeSpace();
		if (minFreeSpace <= 0) {
			return;
		}
		Long free = storage.freeSpace();
		if (free == null) {
			// Object stores have no capacity to report; there is nothing to check.
			return;
		}
		if (free - size < minFreeSpace) {
			metrics.recordUploadRejected(REASON_NO_SPACE);
			throw new LoomRestException(507, LoomRestErrorCode.INTERNAL_ERROR,
				"Not enough space in " + storage.describe() + ": " + free + " bytes free, the upload needs " + size
					+ " and " + minFreeSpace + " must remain (LOOM_STORAGE_MIN_FREE_SPACE).");
		}
	}

	/**
	 * How healthy a backend's remaining capacity is, from the same two thresholds {@link #checkUpload} enforces.
	 *
	 * @param freeBytes the backend's reported free space, or null when it cannot say
	 */
	public Watermark evaluate(Long freeBytes) {
		if (freeBytes == null) {
			// Deliberately not OK. A bucket reports no capacity, and painting it green would be an answer to a
			// question that was never asked - the screen must say "cannot tell" rather than "fine".
			return Watermark.UNKNOWN;
		}
		long minFreeSpace = options.getStorage().getMinFreeSpace();
		if (minFreeSpace > 0 && freeBytes < minFreeSpace) {
			return Watermark.CRITICAL;
		}
		long warnFreeSpace = options.getStorage().getWarnFreeSpace();
		if (warnFreeSpace > 0 && freeBytes < warnFreeSpace) {
			return Watermark.WARN;
		}
		return Watermark.OK;
	}

	/**
	 * How close a storage backend is to full.
	 *
	 * <p>
	 * The {@code severity} is what {@code loom_storage_watermark} exports, ordered so that a {@code max()} across pools reads as "how bad is the worst
	 * one" rather than as an arbitrary enum ordinal — the same encoding {@code loom_node_circuit_breaker_state} uses.
	 * </p>
	 */
	public enum Watermark {

		/** The backend cannot report capacity. Object stores are always this. */
		UNKNOWN(-1),

		/** Above both watermarks. */
		OK(0),

		/** Below {@code LOOM_STORAGE_WARN_FREE_SPACE}. Uploads still succeed. */
		WARN(1),

		/** Below {@code LOOM_STORAGE_MIN_FREE_SPACE}. Uploads are being refused with 507. */
		CRITICAL(2);

		private final int severity;

		Watermark(int severity) {
			this.severity = severity;
		}

		public int severity() {
			return severity;
		}
	}
}
