package io.metaloom.cortex.cloud;

import java.util.Objects;

/**
 * A single cloud-drive item as seen by a listing, a delta entry or a metadata read.
 *
 * <p><b>On {@code changeToken}:</b> like an S3 ETag it is an opaque change token - if it differs
 * from the recorded one, the item changed. It is deliberately <em>not</em> a content hash. Google
 * reports {@code md5Checksum} only for binary files and falls back to a monotonic {@code version};
 * Microsoft reports {@code cTag}/{@code eTag}, neither of which is a digest. The value is prefixed
 * with its origin ({@code md5:}, {@code v:}, {@code ctag:}, {@code etag:}) so two namespaces cannot
 * collide inside one index. Never surface it as a hash and never dedup on it.</p>
 *
 * <p><b>On {@code present}:</b> a separate flag rather than {@code size >= 0}, because a Google
 * native document legitimately has no size. {@code S3LoomMedia} uses the size as an existence proxy
 * and that proxy would be wrong here - see {@link CloudLoomMedia#exists()}.</p>
 *
 * @param provider           owning provider
 * @param driveId            owning drive
 * @param fileId             stable provider id; survives renames and moves
 * @param name               provider file name, verbatim (may contain slashes)
 * @param parentId           id of the containing folder, or null at the drive root
 * @param mimeType           provider MIME type, or null when unknown
 * @param changeToken        opaque change token, or null when unknown
 * @param size               size in bytes, or -1 when the provider reports none
 * @param lastModifiedMillis last-modified epoch millis, or 0 when unknown
 * @param folder             whether this item is a folder
 * @param trashed            whether the item sits in the trash/recycle bin
 * @param exportMimeType     the MIME type this item must be exported as because it has no
 *                           downloadable bytes (Google native documents), or null
 * @param present            whether the provider actually showed us this item
 */
public record CloudFileRef(
	CloudProviderId provider,
	String driveId,
	String fileId,
	String name,
	String parentId,
	String mimeType,
	String changeToken,
	long size,
	long lastModifiedMillis,
	boolean folder,
	boolean trashed,
	String exportMimeType,
	boolean present) {

	public CloudFileRef {
		if (provider == null) {
			throw new IllegalArgumentException("A cloud provider must be set");
		}
		if (driveId == null || driveId.isBlank()) {
			throw new IllegalArgumentException("A drive id must be set");
		}
		if (fileId == null || fileId.isBlank()) {
			throw new IllegalArgumentException("A file id must be set");
		}
		if (name == null || name.isBlank()) {
			name = fileId;
		}
	}

	/**
	 * A handle for an item the provider could not find, so that a vanished file yields a normal
	 * "does not exist" rather than a transport failure.
	 *
	 * @param uri the reference that could not be resolved
	 * @return an absent ref
	 */
	public static CloudFileRef absent(CloudUri uri) {
		return new CloudFileRef(uri.provider(), uri.driveId(), uri.fileId(), uri.fileName(), null,
			null, null, -1, 0, false, false, null, false);
	}

	public CloudUri uri() {
		return new CloudUri(provider, driveId, fileId, effectiveName());
	}

	public String reference() {
		return uri().toReference();
	}

	/**
	 * The name a materialized copy should carry.
	 *
	 * <p>An exported Google document arrives as different bytes in a different format, so it also
	 * needs a different extension - a {@code .pdf} export named {@code Q3 Report} would be
	 * unrecognisable to every downstream node.</p>
	 *
	 * @return the file name including the export extension where one applies
	 */
	public String effectiveName() {
		if (exportMimeType == null) {
			return name;
		}
		String extension = CloudContentTypes.extensionFor(exportMimeType);
		return name.endsWith(extension) ? name : name + extension;
	}

	/**
	 * Whether this item differs in content from a previously indexed one.
	 *
	 * <p>A null token on either side falls back to comparing size alone, so an item whose provider
	 * withheld a token degrades to size-only detection rather than being reported as modified on
	 * every run. A {@code -1} size on both sides is therefore "no evidence of change".</p>
	 *
	 * @param indexedToken change token recorded in the index
	 * @param indexedSize  size recorded in the index
	 * @return true when the item should be reported as MODIFIED
	 */
	public boolean differsFrom(String indexedToken, long indexedSize) {
		if (size != indexedSize) {
			return true;
		}
		if (changeToken == null || indexedToken == null) {
			return false;
		}
		return !changeToken.equals(indexedToken);
	}

	/**
	 * Whether this item sits somewhere else than a previously indexed one.
	 *
	 * <p>This is what S3 cannot do: object storage has no stable identity, so a rename there is
	 * indistinguishable from a delete plus an add. A cloud file id survives both, so comparing the
	 * parent and the name against the index yields a genuine MOVED.</p>
	 *
	 * @param indexedParentId parent recorded in the index
	 * @param indexedName     name recorded in the index
	 * @return true when the item was renamed or re-parented
	 */
	public boolean movedFrom(String indexedParentId, String indexedName) {
		return !Objects.equals(parentId, indexedParentId) || !Objects.equals(name, indexedName);
	}

	/**
	 * @return true when the item must be exported rather than downloaded
	 */
	public boolean requiresExport() {
		return exportMimeType != null;
	}
}
