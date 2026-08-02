package io.metaloom.cortex.cloud.gdrive;

import java.util.Map;

/**
 * Google's native document types, and what to export them as.
 *
 * <p>A Doc, Sheet, Slide deck or Drawing is not a file with bytes: {@code files.list} reports no
 * {@code size}, {@code alt=media} answers {@code 403 fileNotDownloadable}, and the only way to read
 * one is {@code files/{id}/export}. That export is capped at 10 MB of output and is lossy by
 * definition, which is why the node option that enables it defaults to off - with it off, native
 * documents are filtered out during the scan and never enter the index.</p>
 */
public final class GoogleExportFormats {

	/** Every native type shares this MIME prefix, including folders and shortcuts. */
	public static final String NATIVE_PREFIX = "application/vnd.google-apps.";

	public static final String FOLDER_MIME = "application/vnd.google-apps.folder";
	public static final String SHORTCUT_MIME = "application/vnd.google-apps.shortcut";

	private static final Map<String, String> EXPORT_MIMES = Map.of(
		"application/vnd.google-apps.document", "application/pdf",
		"application/vnd.google-apps.presentation", "application/pdf",
		"application/vnd.google-apps.drawing", "image/png",
		"application/vnd.google-apps.spreadsheet", "text/csv",
		"application/vnd.google-apps.script", "application/json");

	private GoogleExportFormats() {
	}

	/**
	 * @param mimeType a Drive MIME type
	 * @return true when this is one of Google's own document types
	 */
	public static boolean isNative(String mimeType) {
		return mimeType != null && mimeType.startsWith(NATIVE_PREFIX);
	}

	/**
	 * @param mimeType a Drive MIME type
	 * @return true when this item is a folder
	 */
	public static boolean isFolder(String mimeType) {
		return FOLDER_MIME.equals(mimeType);
	}

	/**
	 * The MIME type a native document must be exported as.
	 *
	 * @param mimeType a Drive MIME type
	 * @return the export MIME type, or null when the item has real bytes (or cannot be exported at
	 *         all, which is the case for folders and shortcuts)
	 */
	public static String exportMimeFor(String mimeType) {
		if (!isNative(mimeType)) {
			return null;
		}
		return EXPORT_MIMES.get(mimeType);
	}
}
