package io.metaloom.cortex.s3;

/**
 * A single object as seen by a listing or a HEAD.
 *
 * <p><b>On {@code etag}:</b> it is used purely as an opaque change token - if it differs from
 * the recorded one, the object changed. It is deliberately <em>not</em> treated as a content
 * hash, because for multipart uploads S3 returns {@code <md5-of-md5s>-<partcount>} rather than
 * the object's MD5. Never surface it as MD5 and never dedup on it.</p>
 *
 * @param bucket             owning bucket
 * @param key                object key, literal (already URL-decoded when it came from an event)
 * @param etag               change token, quotes stripped; may be null when unknown
 * @param size               size in bytes, or -1 when unknown
 * @param lastModifiedMillis last-modified epoch millis, or 0 when unknown
 */
public record S3ObjectRef(String bucket, String key, String etag, long size, long lastModifiedMillis) {

	public S3ObjectRef {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("An S3 bucket must be set");
		}
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("An S3 object key must be set");
		}
		etag = normalizeEtag(etag);
	}

	/**
	 * Strip the quotes S3 wraps around ETags, and the weak-validator prefix some gateways add.
	 *
	 * @param etag raw etag
	 * @return normalized etag, or null
	 */
	public static String normalizeEtag(String etag) {
		if (etag == null) {
			return null;
		}
		String value = etag.trim();
		if (value.startsWith("W/")) {
			value = value.substring(2);
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		return value.isBlank() ? null : value;
	}

	public S3Uri uri() {
		return new S3Uri(bucket, key);
	}

	public String reference() {
		return uri().toReference();
	}

	/**
	 * Whether this object differs from a previously indexed one.
	 *
	 * <p>A null etag on either side falls back to comparing size alone, so a store that does not
	 * return ETags degrades to size-only change detection rather than reporting everything as
	 * modified on every run.</p>
	 *
	 * @param indexedEtag etag recorded in the index
	 * @param indexedSize size recorded in the index
	 * @return true when the object should be reported as MODIFIED
	 */
	public boolean differsFrom(String indexedEtag, long indexedSize) {
		if (size != indexedSize) {
			return true;
		}
		if (etag == null || indexedEtag == null) {
			return false;
		}
		return !etag.equals(indexedEtag);
	}
}
