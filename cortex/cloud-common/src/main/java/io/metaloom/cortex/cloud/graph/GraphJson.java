package io.metaloom.cortex.cloud.graph;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.vertx.core.json.JsonObject;

/**
 * The Microsoft Graph {@code $select} mask, and the mapping from a {@code driveItem} to a
 * {@link CloudFileRef}.
 */
public final class GraphJson {

	/**
	 * Fields requested for every item.
	 *
	 * <p>{@code cTag} changes only when the <em>content</em> changes and {@code eTag} also on
	 * metadata edits, so the change token prefers the former - a renamed file should be reported as
	 * MOVED, not MODIFIED, and the parent comparison is what decides that.</p>
	 *
	 * <p>{@code deleted} has to be selected explicitly or delta entries arrive without their
	 * tombstone facet.</p>
	 */
	public static final String SELECT =
		"id,name,size,file,folder,eTag,cTag,lastModifiedDateTime,parentReference,deleted";

	/** Graph hands out a short-lived pre-authenticated URL under this annotation. */
	public static final String DOWNLOAD_URL = "@microsoft.graph.downloadUrl";

	private GraphJson() {
	}

	/**
	 * Map one {@code driveItem}.
	 *
	 * @param item    the JSON resource
	 * @param driveId the drive it was read from
	 * @return the mapped ref, or null when the resource is unusable
	 */
	public static CloudFileRef toRef(JsonObject item, String driveId) {
		if (item == null || item.getString("id") == null) {
			return null;
		}
		boolean folder = item.getJsonObject("folder") != null;
		JsonObject file = item.getJsonObject("file");
		String mimeType = file == null ? null : file.getString("mimeType");

		return new CloudFileRef(
			CloudProviderId.ONEDRIVE,
			driveId,
			item.getString("id"),
			item.getString("name"),
			parentIdOf(item),
			folder ? "application/vnd.microsoft.graph.folder" : mimeType,
			changeTokenOf(item),
			item.getLong("size", -1L),
			modifiedMillis(item.getString("lastModifiedDateTime")),
			folder,
			// Graph has no "trashed" state on a live item: a recycled item simply stops being
			// returned, and shows up in the delta feed with a deleted facet instead.
			false,
			// Every OneDrive item has real bytes, so nothing here ever needs exporting.
			null,
			true);
	}

	/**
	 * @param item a delta entry
	 * @return true when this entry is a tombstone
	 */
	public static boolean isDeleted(JsonObject item) {
		return item != null && item.getJsonObject("deleted") != null;
	}

	/**
	 * @param item a driveItem
	 * @return the pre-authenticated download URL Graph attached, or null
	 */
	public static String downloadUrl(JsonObject item) {
		return item == null ? null : item.getString(DOWNLOAD_URL);
	}

	static String parentIdOf(JsonObject item) {
		JsonObject parent = item.getJsonObject("parentReference");
		return parent == null ? null : parent.getString("id");
	}

	/**
	 * The opaque change token, prefixed with its origin so it cannot be confused with Google's
	 * inside one index.
	 */
	static String changeTokenOf(JsonObject item) {
		String cTag = item.getString("cTag");
		if (cTag != null && !cTag.isBlank()) {
			return "ctag:" + cTag;
		}
		String eTag = item.getString("eTag");
		return eTag == null || eTag.isBlank() ? null : "etag:" + eTag;
	}

	static long modifiedMillis(String iso8601) {
		if (iso8601 == null || iso8601.isBlank()) {
			return 0;
		}
		try {
			return Instant.parse(iso8601).toEpochMilli();
		} catch (DateTimeParseException e) {
			return 0;
		}
	}
}
