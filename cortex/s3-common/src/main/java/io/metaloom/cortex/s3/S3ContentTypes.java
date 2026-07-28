package io.metaloom.cortex.s3;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Maps a file name to a content type.
 *
 * <p>This exists because there is no reusable MIME helper anywhere in the workspace:
 * {@code io.metaloom.utils.fs} offers only {@code isVideo}/{@code isImage}/{@code isAudio}
 * predicates, {@code loom-shared} has none, and the mapping is duplicated as private switch
 * statements in {@code DaoAssetSink} and {@code SessionFsEndpointService}.</p>
 *
 * <p>It serves two purposes for the S3 sink, and both matter: the {@code Content-Type} stored on
 * the object, and the {@code mimeType} stamped on the Loom asset created for it.</p>
 *
 * <p><b>Why not {@link java.nio.file.Files#probeContentType}:</b> it is platform-dependent (it
 * reads {@code /etc/mime.types}) and commonly returns null inside slim containers, so the stored
 * content type would differ between a developer's machine and production - a difference nobody
 * notices until someone opens the object in a browser.</p>
 */
public final class S3ContentTypes {

	public static final String DEFAULT = "application/octet-stream";

	private S3ContentTypes() {
	}

	/**
	 * @param file a local file
	 * @return the content type, never null
	 */
	public static String of(Path file) {
		return file == null ? DEFAULT : of(file.getFileName().toString());
	}

	/**
	 * @param fileName a file name, with or without directories
	 * @return the content type, never null
	 */
	public static String of(String fileName) {
		if (fileName == null) {
			return DEFAULT;
		}
		String name = fileName.toLowerCase(Locale.ROOT);
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		int dot = name.lastIndexOf('.');
		String extension = dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);

		return switch (extension) {
			// ThumbnailNode writes a JPEG under a .thumb name (PreviewGenerator.save ->
			// ImageUtils.saveJPG in video4j). Leaving it as octet-stream would make every browser
			// download the contact sheet instead of rendering it, and would put the wrong mimeType
			// on the asset. If video4j ever changes that format, this line silently lies.
			case "thumb", "jpg", "jpeg" -> "image/jpeg";
			case "png" -> "image/png";
			case "webp" -> "image/webp";
			case "gif" -> "image/gif";
			case "tif", "tiff" -> "image/tiff";
			case "bmp" -> "image/bmp";
			case "svg" -> "image/svg+xml";

			case "wav" -> "audio/wav";
			case "mp3" -> "audio/mpeg";
			case "flac" -> "audio/flac";
			case "ogg", "oga" -> "audio/ogg";
			case "m4a" -> "audio/mp4";
			case "aac" -> "audio/aac";

			case "mp4", "m4v" -> "video/mp4";
			case "mov" -> "video/quicktime";
			case "mkv" -> "video/x-matroska";
			case "webm" -> "video/webm";
			case "avi" -> "video/x-msvideo";

			case "json" -> "application/json";
			case "txt" -> "text/plain";
			case "csv" -> "text/csv";
			case "xml" -> "application/xml";
			case "pdf" -> "application/pdf";
			case "zip" -> "application/zip";

			default -> DEFAULT;
		};
	}
}
