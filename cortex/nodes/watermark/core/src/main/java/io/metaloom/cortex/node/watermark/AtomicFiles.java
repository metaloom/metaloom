package io.metaloom.cortex.node.watermark;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishing a finished artifact into the local cache without ever exposing a partial one.
 *
 * <p>
 * Both the image and the video path write to a {@code .part} sibling and then move it into place. That matters more here than for the analysis nodes: an
 * artifact path is cached and handed downstream to {@code s3-sink}, so a truncated PNG or a half-muxed MP4 left behind by a killed worker would be
 * uploaded and registered as a Loom asset. Mirrors what {@code S3MediaMaterializer} does for downloads.
 * </p>
 */
final class AtomicFiles {

	private AtomicFiles() {
	}

	/**
	 * The temporary sibling to write before publishing {@code target}.
	 *
	 * <p>
	 * The marker goes <strong>before</strong> the extension - {@code clip.part.mp4}, not {@code clip.mp4.part}. That is not cosmetic: ffmpeg chooses its
	 * output muxer from the file name's extension, and writing to {@code .mp4.part} fails with <em>"Unable to choose an output format"</em>. Keeping the real
	 * extension last means the same helper serves the image and the video path.
	 * </p>
	 *
	 * @param target the final destination
	 * @return the temporary path to write first
	 */
	static Path partFor(Path target) {
		String name = target.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String part = dot > 0 ? name.substring(0, dot) + ".part" + name.substring(dot) : name + ".part";
		return target.resolveSibling(part);
	}

	/**
	 * Move {@code source} onto {@code target}, replacing any existing file.
	 *
	 * <p>
	 * Falls back to a non-atomic replacing move when the filesystem cannot do it atomically - some network filesystems cannot, and a node that refused to
	 * produce anything there would be worse than one that leaves a narrow window.
	 * </p>
	 *
	 * @param source the completed temporary file
	 * @param target the destination path
	 * @throws IOException when the move fails
	 */
	static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
