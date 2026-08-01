package io.metaloom.loom.storage.s3;

/**
 * An {@code s3://bucket/key} reference, as stored in {@code asset_location.path} for S3-backed pools.
 *
 * <p>
 * 🔴 <b>This grammar is a contract with Cortex, not an internal detail.</b> {@code io.metaloom.cortex.s3.S3Uri} parses exactly this form, and
 * {@code S3MediaMaterializer} is what lets a worker process an S3-hosted asset at all. Storing a bare object key here instead would make every asset
 * uploaded into an S3 library unprocessable by any pipeline. The two classes are duplicated rather than shared because a
 * {@code loom-service-s3 → cortex-s3-common} dependency would tie the server's build to the worker's; {@code S3LocatorTest} pins the grammar on this
 * side.
 * </p>
 *
 * @param bucket
 *            bucket name, never blank and never containing a slash
 * @param key
 *            object key, verbatim — S3 keys may contain slashes, spaces and unicode, and none of that is normalised
 */
public record S3Locator(String bucket, String key) {

	public static final String SCHEME = "s3://";

	public S3Locator {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("An S3 bucket must be set");
		}
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("An S3 object key must be set");
		}
		if (bucket.indexOf('/') >= 0) {
			throw new IllegalArgumentException("An S3 bucket name must not contain a slash: " + bucket);
		}
	}

	/**
	 * @param locator
	 *            any locator string
	 * @return whether it is an {@code s3://} reference
	 */
	public static boolean isS3(String locator) {
		return locator != null && locator.startsWith(SCHEME);
	}

	/**
	 * @param locator
	 *            an {@code s3://bucket/key} reference
	 * @return the parsed locator
	 * @throws IllegalArgumentException
	 *             when the reference is not well-formed
	 */
	public static S3Locator parse(String locator) {
		if (!isS3(locator)) {
			throw new IllegalArgumentException("Not an S3 reference: " + locator);
		}
		String remainder = locator.substring(SCHEME.length());
		int slash = remainder.indexOf('/');
		if (slash < 0) {
			throw new IllegalArgumentException("S3 reference is missing an object key: " + locator);
		}
		return new S3Locator(remainder.substring(0, slash), remainder.substring(slash + 1));
	}

	/**
	 * @return the {@code s3://bucket/key} form
	 */
	public String toReference() {
		return SCHEME + bucket + "/" + key;
	}

	@Override
	public String toString() {
		return toReference();
	}
}
