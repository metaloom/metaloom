package io.metaloom.cortex.cloud;

/**
 * A {@code <scheme>://<driveId>/<fileId>/<fileName>} reference to a cloud-drive file.
 *
 * <p>Like {@code S3Uri} this is a record over a {@link String} rather than a
 * {@link java.nio.file.Path}, because {@code Paths.get("gdrive://d/f/x.mp4")} collapses the double
 * slash and a URI cannot survive the path API.</p>
 *
 * <h2>Why the file name is in the reference at all</h2>
 *
 * <p>A cloud file is identified by an opaque id with no extension, and two independent consumers
 * need an extension to do their job:</p>
 * <ul>
 * <li>cortex detects media type from the path ({@code FilterHelper.isVideo(path())}), so a
 * materialized file without its extension is invisible to every media node;</li>
 * <li>Loom's asset sink derives both the asset filename and its MIME type from
 * {@code Paths.get(reference).getFileName()}.</li>
 * </ul>
 *
 * <p>Putting the name in the <em>last</em> segment satisfies both. The {@code fileId} segment is
 * what actually identifies the file, so the name segment is addressing decoration: it is sanitized
 * (Drive file names may legitimately contain {@code /}) and the true name lives in the scan index.
 * Percent-encoding was rejected because {@code %2F} would then leak into the asset's filename.</p>
 */
public record CloudUri(CloudProviderId provider, String driveId, String fileId, String fileName) {

	/**
	 * Placeholder drive id used when Google is addressed without one, i.e. the credential's own
	 * "My Drive". Keeping a literal here is what makes every reference three segments, so parsing
	 * never has to special-case a missing drive.
	 */
	public static final String MY_DRIVE = "my";

	public CloudUri {
		if (provider == null) {
			throw new IllegalArgumentException("A cloud provider must be set");
		}
		if (driveId == null || driveId.isBlank()) {
			throw new IllegalArgumentException("A drive id must be set");
		}
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalArgumentException("A file id must be set");
		}
		if (driveId.indexOf('/') >= 0) {
			throw new IllegalArgumentException("A drive id must not contain a slash: " + driveId);
		}
		if (fileId.indexOf('/') >= 0) {
			throw new IllegalArgumentException("A file id must not contain a slash: " + fileId);
		}
		fileName = sanitizeName(fileName);
	}

	/**
	 * Make a provider file name usable as the last segment of a reference.
	 *
	 * <p>Slashes and control characters become underscores. The result is not required to round
	 * trip - the real name is kept in the scan index - it only has to be a plausible file name so
	 * that extension-based media detection and Loom's filename/MIME derivation work.</p>
	 *
	 * @param name the provider's file name, may be null or blank
	 * @return a safe single path segment, never null and never blank
	 */
	public static String sanitizeName(String name) {
		if (name == null || name.isBlank()) {
			return "file";
		}
		StringBuilder builder = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			builder.append(c == '/' || c == '\\' || c < ' ' ? '_' : c);
		}
		String sanitized = builder.toString().trim();
		return sanitized.isEmpty() ? "file" : sanitized;
	}

	/**
	 * @param reference any media reference
	 * @return true when the reference belongs to a known cloud provider
	 */
	public static boolean isCloud(String reference) {
		return providerOf(reference) != null;
	}

	/**
	 * @param reference any media reference
	 * @return the provider owning the reference's scheme, or null
	 */
	public static CloudProviderId providerOf(String reference) {
		if (reference == null) {
			return null;
		}
		int marker = reference.indexOf("://");
		if (marker <= 0) {
			return null;
		}
		return CloudProviderId.forScheme(reference.substring(0, marker));
	}

	/**
	 * Parse a {@code <scheme>://<driveId>/<fileId>/<fileName>} reference.
	 *
	 * @param reference the URI
	 * @return the parsed URI
	 * @throws IllegalArgumentException when the reference is not a well-formed cloud URI
	 */
	public static CloudUri parse(String reference) {
		CloudProviderId provider = providerOf(reference);
		if (provider == null) {
			throw new IllegalArgumentException("Not a cloud media reference: " + reference);
		}
		String remainder = reference.substring(provider.scheme().length() + 3);
		int firstSlash = remainder.indexOf('/');
		if (firstSlash < 0) {
			throw new IllegalArgumentException("Cloud reference is missing a file id: " + reference);
		}
		String driveId = remainder.substring(0, firstSlash);
		String rest = remainder.substring(firstSlash + 1);
		int secondSlash = rest.indexOf('/');
		if (secondSlash < 0) {
			// A name-less reference is still addressable; it just loses media-type detection.
			return new CloudUri(provider, driveId, rest, null);
		}
		return new CloudUri(provider, driveId, rest.substring(0, secondSlash), rest.substring(secondSlash + 1));
	}

	/**
	 * @return the full reference string
	 */
	public String toReference() {
		return provider.scheme() + "://" + driveId + "/" + fileId + "/" + fileName;
	}

	/**
	 * The file extension including the leading dot, or an empty string.
	 *
	 * <p>Load-bearing for media-type detection, so the same guard {@code S3Uri} uses is applied: a
	 * long or non-alphanumeric trailing segment is a dot inside a name, not an extension.</p>
	 *
	 * @return the extension with its dot (e.g. {@code .mp4}), or {@code ""}
	 */
	public String extension() {
		int dot = fileName.lastIndexOf('.');
		if (dot <= 0 || dot == fileName.length() - 1) {
			return "";
		}
		String ext = fileName.substring(dot);
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

	/**
	 * @return true when this reference addresses the credential's own drive rather than a named one
	 */
	public boolean isMyDrive() {
		return MY_DRIVE.equals(driveId);
	}

	@Override
	public String toString() {
		return toReference();
	}
}
