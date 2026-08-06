package io.metaloom.cortex.s3;

import java.nio.file.Path;

import io.metaloom.cortex.common.media.MediaContentTypes;

/**
 * Maps a file name to a content type.
 *
 * <p>It serves two purposes for the S3 sink, and both matter: the {@code Content-Type} stored on
 * the object, and the {@code mimeType} stamped on the Loom asset created for it.</p>
 *
 * <p>The table itself moved to {@link MediaContentTypes} in {@code cortex-common} once the
 * {@code filter} node needed the same answer for its {@code MIME} bucketing — a node that must not
 * drag the AWS SDK onto its classpath to learn that {@code .png} is an image. This class stays as
 * the S3-facing name so the sink and its tests are untouched.</p>
 */
public final class S3ContentTypes {

	public static final String DEFAULT = MediaContentTypes.DEFAULT;

	private S3ContentTypes() {
	}

	/**
	 * @param file a local file
	 * @return the content type, never null
	 */
	public static String of(Path file) {
		return MediaContentTypes.of(file);
	}

	/**
	 * @param fileName a file name, with or without directories
	 * @return the content type, never null
	 */
	public static String of(String fileName) {
		return MediaContentTypes.of(fileName);
	}
}
