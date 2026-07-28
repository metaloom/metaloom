package io.metaloom.cortex.s3.event;

/**
 * A transport that delivers S3 change hints into the {@link S3EventBuffer}.
 *
 * <p>Two exist: a webhook route on the worker's monitoring server (what MinIO posts to) and an
 * SQS poller (what AWS feeds). Both end in the same buffer, so the source node is unaware of
 * which one is in use.</p>
 */
public interface S3EventSource {

	/** Begin delivering hints. Must not block. */
	void start();

	/** Stop delivering hints and release any resources. Must be safe to call more than once. */
	void stop();
}
