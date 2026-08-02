package io.metaloom.cortex.cloud.http;

import java.io.IOException;

/**
 * A cloud API answered with an error status.
 *
 * <p>Carries the status and the provider's own error code so callers can tell apart the cases that
 * matter without re-parsing the body: a stale delta cursor (Google 404, Microsoft
 * {@code 410 resyncRequired}) has to trigger a full walk, an over-size export has to fail one item,
 * and throttling has to be retried - which on Google arrives as a {@code 403}, not a {@code 429}.</p>
 */
public class CloudApiException extends IOException {

	private static final long serialVersionUID = 1L;

	/** Microsoft's "your delta cursor is too old, start over" code. */
	public static final String RESYNC_REQUIRED = "resyncRequired";

	/** Google's throttling reasons, which arrive with a 403 rather than a 429. */
	public static final String RATE_LIMIT_EXCEEDED = "rateLimitExceeded";
	public static final String USER_RATE_LIMIT_EXCEEDED = "userRateLimitExceeded";

	/** Google's "this file has no bytes, export it instead". */
	public static final String FILE_NOT_DOWNLOADABLE = "fileNotDownloadable";

	/** Google's export size cap (10 MB of exported content). */
	public static final String EXPORT_SIZE_LIMIT_EXCEEDED = "exportSizeLimitExceeded";

	private final int status;
	private final String errorCode;

	public CloudApiException(int status, String errorCode, String message) {
		super(message);
		this.status = status;
		this.errorCode = errorCode;
	}

	/**
	 * @return the HTTP status code
	 */
	public int status() {
		return status;
	}

	/**
	 * @return the provider's error code or reason, or null when the body carried none
	 */
	public String errorCode() {
		return errorCode;
	}

	/**
	 * @return true when this error means the caller's delta cursor is no longer usable
	 */
	public boolean isDeltaTokenExpired() {
		if (status == 410) {
			return true;
		}
		// Google answers a stale changes.list pageToken with 404.
		return status == 404 && RESYNC_REQUIRED.equalsIgnoreCase(errorCode);
	}

	/**
	 * Whether retrying could plausibly succeed.
	 *
	 * <p>The {@code 403} case is the trap: Google reports throttling as {@code 403} with a
	 * {@code rateLimitExceeded} reason. Treating every {@code 403} as fatal makes Drive throttling
	 * look like a permission failure and kills the run; treating every {@code 403} as retryable
	 * would spin on a genuine permission error. Only the two throttling reasons qualify.</p>
	 *
	 * @return true when the request should be retried after a backoff
	 */
	public boolean isRetryable() {
		if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
			return true;
		}
		return status == 403
			&& (RATE_LIMIT_EXCEEDED.equalsIgnoreCase(errorCode) || USER_RATE_LIMIT_EXCEEDED.equalsIgnoreCase(errorCode));
	}

	/**
	 * @return true when the item exists but has no downloadable bytes
	 */
	public boolean isNotDownloadable() {
		return FILE_NOT_DOWNLOADABLE.equalsIgnoreCase(errorCode);
	}
}
