package io.metaloom.cortex.s3.event;

/**
 * A single "this object changed" hint carried by a bucket notification.
 *
 * <p>Deliberately a <em>hint</em>, not a fact. The event payload does carry an etag and size, but
 * events can arrive out of order, be duplicated, or be lost entirely, so the scanner re-checks the
 * object with a HEAD before acting. What the hint really contributes is the <em>key</em> - the
 * thing that lets a run skip listing the bucket.</p>
 *
 * @param bucket  owning bucket
 * @param key     object key, already URL-decoded
 * @param removed true for an {@code ObjectRemoved:*} event
 */
public record S3ChangeHint(String bucket, String key, boolean removed) {

	public S3ChangeHint {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("An S3 bucket must be set");
		}
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("An S3 object key must be set");
		}
	}

	public static S3ChangeHint created(String bucket, String key) {
		return new S3ChangeHint(bucket, key, false);
	}

	public static S3ChangeHint removed(String bucket, String key) {
		return new S3ChangeHint(bucket, key, true);
	}
}
