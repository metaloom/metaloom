package io.metaloom.cortex.cloud;

import java.util.Map;

/**
 * MIME type to file extension.
 *
 * <p>The reverse of {@code S3ContentTypes}, and needed for a reason S3 never has: a Google native
 * document carries no extension in its name and no downloadable bytes, so the extension of a
 * materialized copy can only come from the MIME type it was exported as.</p>
 */
public final class CloudContentTypes {

	private static final String FALLBACK = ".bin";

	private static final Map<String, String> EXTENSIONS = Map.ofEntries(
		Map.entry("application/pdf", ".pdf"),
		Map.entry("text/csv", ".csv"),
		Map.entry("text/plain", ".txt"),
		Map.entry("text/html", ".html"),
		Map.entry("application/rtf", ".rtf"),
		Map.entry("application/zip", ".zip"),
		Map.entry("image/png", ".png"),
		Map.entry("image/jpeg", ".jpg"),
		Map.entry("image/gif", ".gif"),
		Map.entry("image/webp", ".webp"),
		Map.entry("image/svg+xml", ".svg"),
		Map.entry("video/mp4", ".mp4"),
		Map.entry("video/quicktime", ".mov"),
		Map.entry("video/x-matroska", ".mkv"),
		Map.entry("audio/mpeg", ".mp3"),
		Map.entry("audio/wav", ".wav"),
		Map.entry("audio/flac", ".flac"),
		Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
		Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
		Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"));

	private CloudContentTypes() {
	}

	/**
	 * @param mimeType a MIME type, possibly with parameters and mixed casing
	 * @return the extension including its dot, or {@code .bin} when unknown
	 */
	public static String extensionFor(String mimeType) {
		if (mimeType == null || mimeType.isBlank()) {
			return FALLBACK;
		}
		String normalized = mimeType.trim().toLowerCase();
		int semicolon = normalized.indexOf(';');
		if (semicolon >= 0) {
			normalized = normalized.substring(0, semicolon).trim();
		}
		return EXTENSIONS.getOrDefault(normalized, FALLBACK);
	}
}
