package io.metaloom.cortex.cloud.gdrive;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The Drive v3 field masks, and the mapping from a {@code files} resource to a
 * {@link CloudFileRef}.
 *
 * <p>Kept apart from the store so the mapping can be tested against captured JSON without a
 * client, and so the field masks - which are easy to get wrong and silently return less than you
 * asked for - live in exactly one place.</p>
 */
public final class GoogleDriveJson {

	/**
	 * Fields requested for every file.
	 *
	 * <p>{@code md5Checksum} is present only for binary files, {@code version} always - which is
	 * why the change token falls back from one to the other. {@code parents} is what makes MOVED
	 * detectable at all, and {@code trashed} lets the caller decide rather than the query.</p>
	 */
	public static final String FILE_FIELDS =
		"id,name,mimeType,size,md5Checksum,version,modifiedTime,parents,trashed";

	public static final String LIST_FIELDS = "nextPageToken,files(" + FILE_FIELDS + ")";

	public static final String CHANGES_FIELDS =
		"nextPageToken,newStartPageToken,changes(fileId,removed,file(" + FILE_FIELDS + "))";

	private GoogleDriveJson() {
	}

	/**
	 * Map one {@code files} resource.
	 *
	 * @param file             the JSON resource
	 * @param driveId          the drive it was read from
	 * @param exportNativeDocs whether native documents should be given an export MIME type (and so
	 *                         become readable) or left without one
	 * @return the mapped ref, or null when the resource is unusable
	 */
	public static CloudFileRef toRef(JsonObject file, String driveId, boolean exportNativeDocs) {
		if (file == null || file.getString("id") == null) {
			return null;
		}
		String mimeType = file.getString("mimeType");
		boolean folder = GoogleExportFormats.isFolder(mimeType);
		String exportMime = exportNativeDocs && !folder ? GoogleExportFormats.exportMimeFor(mimeType) : null;

		return new CloudFileRef(
			CloudProviderId.GDRIVE,
			driveId,
			file.getString("id"),
			file.getString("name"),
			firstParent(file.getJsonArray("parents")),
			mimeType,
			changeTokenOf(file),
			// A native document reports no size at all; -1 is the honest answer and must never be
			// resolved by downloading the file.
			file.getLong("size", -1L),
			modifiedMillis(file.getString("modifiedTime")),
			folder,
			Boolean.TRUE.equals(file.getBoolean("trashed")),
			exportMime,
			true);
	}

	/**
	 * Drive allows a file to sit in several folders at once. The scan tree needs a single parent,
	 * and the first is the one Drive itself treats as canonical for a file created normally.
	 */
	static String firstParent(JsonArray parents) {
		if (parents == null || parents.isEmpty()) {
			return null;
		}
		return parents.getString(0);
	}

	/**
	 * The opaque change token, prefixed with its origin so an {@code md5:} value can never be
	 * confused with a {@code v:} one inside a persisted index.
	 *
	 * <p>⚠️ The two differ in more than availability. {@code md5Checksum} changes only when the
	 * <em>content</em> does, so a rename keeps it and the scanner reports {@code MOVED}.
	 * {@code version} is bumped by Drive on <em>every</em> change, metadata included, so an item
	 * that falls back to it reports a rename as {@code MODIFIED} instead. That affects only items
	 * with no checksum — native Google documents and shortcuts — and errs toward over-reporting,
	 * which is the safe direction.</p>
	 */
	static String changeTokenOf(JsonObject file) {
		String md5 = file.getString("md5Checksum");
		if (md5 != null && !md5.isBlank()) {
			return "md5:" + md5;
		}
		// version is a monotonically increasing counter Drive bumps on every change, metadata
		// included. Always present, so this is the universal fallback.
		String version = file.getString("version");
		if (version == null) {
			Long numeric = file.getLong("version");
			version = numeric == null ? null : String.valueOf(numeric);
		}
		return version == null || version.isBlank() ? null : "v:" + version;
	}

	static long modifiedMillis(String rfc3339) {
		if (rfc3339 == null || rfc3339.isBlank()) {
			return 0;
		}
		try {
			return Instant.parse(rfc3339).toEpochMilli();
		} catch (DateTimeParseException e) {
			return 0;
		}
	}
}
