package io.metaloom.cortex.s3;

import java.util.Objects;

/**
 * An {@code s3://bucket/key} reference.
 *
 * <p>This is the form a {@link io.metaloom.cortex.api.media.ProcessableMedia#reference()}
 * takes for object-storage media, and the reason references are strings rather than
 * {@link java.nio.file.Path}s: {@code Paths.get("s3://bucket/key")} collapses the double
 * slash to {@code s3:/bucket/key}, so a URI cannot survive the path API.</p>
 *
 * <p>The key is kept verbatim - S3 keys may contain slashes, spaces and unicode, and none
 * of that is normalised here. Callers that read keys out of S3 <em>event</em> payloads must
 * URL-decode them first; keys from a listing are already literal.</p>
 */
public record S3Uri(String bucket, String key) {

	public static final String SCHEME = "s3://";

	public S3Uri {
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
	 * @param reference any media reference
	 * @return true when the reference is an {@code s3://} URI
	 */
	public static boolean isS3(String reference) {
		return reference != null && reference.startsWith(SCHEME);
	}

	/**
	 * Parse an {@code s3://bucket/key} reference.
	 *
	 * @param reference the URI
	 * @return the parsed URI
	 * @throws IllegalArgumentException when the reference is not a well-formed S3 URI
	 */
	public static S3Uri parse(String reference) {
		if (!isS3(reference)) {
			throw new IllegalArgumentException("Not an S3 reference: " + reference);
		}
		String remainder = reference.substring(SCHEME.length());
		int slash = remainder.indexOf('/');
		if (slash < 0) {
			throw new IllegalArgumentException("S3 reference is missing an object key: " + reference);
		}
		return new S3Uri(remainder.substring(0, slash), remainder.substring(slash + 1));
	}

	/**
	 * @return the {@code s3://bucket/key} form
	 */
	public String toReference() {
		return SCHEME + bucket + "/" + key;
	}

	/**
	 * The last path segment of the key, i.e. what a filesystem would call the file name.
	 *
	 * @return the file name, never null
	 */
	public String fileName() {
		int slash = key.lastIndexOf('/');
		return slash < 0 ? key : key.substring(slash + 1);
	}

	/**
	 * The file extension of the key including the leading dot, or an empty string.
	 *
	 * <p>Load-bearing: cortex detects media type from the path extension
	 * ({@code LoomMediaImpl.isVideo()} delegates to {@code FilterHelper.isVideo(path())}),
	 * so a materialized object that loses its extension is invisible to every media node.</p>
	 *
	 * @return the extension with its dot (e.g. {@code .mp4}), or {@code ""}
	 */
	public String extension() {
		String name = fileName();
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return "";
		}
		String ext = name.substring(dot);
		// Guard against a dot inside a key segment that is not really an extension.
		if (ext.length() > 12) {
			return "";
		}
		for (int i = 1; i < ext.length(); i++) {
			if (!Character.isLetterOrDigit(ext.charAt(i))) {
				return "";
			}
		}
		return ext;
	}

	@Override
	public String toString() {
		return toReference();
	}

	public static S3Uri of(String bucket, String key) {
		return new S3Uri(Objects.requireNonNull(bucket), Objects.requireNonNull(key));
	}
}
