package io.metaloom.cortex.api.option;

/**
 * Configuration for the push-based change-detection path.
 *
 * <p>Events make a <em>run</em> cheap - the source drains a buffer of changed keys instead of
 * listing the bucket. They do not <em>start</em> a run; Loom's scheduler still owns that.</p>
 */
public class S3EventOptions {

	/** How change hints reach the worker. */
	public enum Mode {
		/**
		 * The worker exposes an HTTP route on its existing monitoring server and the bucket posts
		 * S3 event JSON to it. This is what MinIO's {@code notify_webhook} target speaks.
		 */
		WEBHOOK,

		/**
		 * The worker long-polls an SQS queue fed by S3 notifications (directly, or via SNS).
		 */
		SQS
	}

	public static final String DEFAULT_WEBHOOK_PATH = "/s3-events";
	public static final String TOKEN_HEADER = "X-Cortex-S3-Token";
	public static final int DEFAULT_MAX_BUFFERED_KEYS = 50_000;

	private boolean enabled;
	private Mode mode = Mode.WEBHOOK;
	private String webhookPath = DEFAULT_WEBHOOK_PATH;
	private String webhookSecret;
	private String queueUrl;
	private int maxBufferedKeys = DEFAULT_MAX_BUFFERED_KEYS;

	public boolean isEnabled() {
		return enabled;
	}

	public S3EventOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public Mode getMode() {
		return mode;
	}

	public S3EventOptions setMode(Mode mode) {
		this.mode = mode == null ? Mode.WEBHOOK : mode;
		return this;
	}

	public String getWebhookPath() {
		return webhookPath;
	}

	public S3EventOptions setWebhookPath(String webhookPath) {
		this.webhookPath = webhookPath == null || webhookPath.isBlank() ? DEFAULT_WEBHOOK_PATH : webhookPath;
		return this;
	}

	/**
	 * @return the shared secret expected in the {@value #TOKEN_HEADER} header; required when the
	 *         webhook mode is enabled, because the route accepts change hints from the network
	 */
	public String getWebhookSecret() {
		return webhookSecret;
	}

	public S3EventOptions setWebhookSecret(String webhookSecret) {
		this.webhookSecret = webhookSecret;
		return this;
	}

	public String getQueueUrl() {
		return queueUrl;
	}

	public S3EventOptions setQueueUrl(String queueUrl) {
		this.queueUrl = queueUrl;
		return this;
	}

	/**
	 * @return the buffer ceiling; on overflow the buffer degrades and the next run is forced onto
	 *         a full listing rather than silently dropping hints
	 */
	public int getMaxBufferedKeys() {
		return maxBufferedKeys;
	}

	public S3EventOptions setMaxBufferedKeys(int maxBufferedKeys) {
		this.maxBufferedKeys = maxBufferedKeys <= 0 ? DEFAULT_MAX_BUFFERED_KEYS : maxBufferedKeys;
		return this;
	}
}
