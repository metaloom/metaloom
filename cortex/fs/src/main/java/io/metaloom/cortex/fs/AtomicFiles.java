package io.metaloom.cortex.fs;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishing a finished file without ever exposing a partial one.
 *
 * <p>
 * Write to a {@code .part} sibling, then move it into place. That matters wherever a path is handed downstream: a truncated PNG or a half-muxed MP4
 * left behind by a killed worker would be uploaded and registered as a Loom asset. It matters even more for a relocation, where the partial file is
 * the only copy of the bytes at the destination.
 * </p>
 *
 * <p>
 * This class was package-private and duplicated verbatim in {@code cortex/nodes/watermark} and {@code cortex/nodes/image-manipulation}. The move node
 * needed the same thing, and its own javadoc had already named a third copy as the point to stop deferring.
 * </p>
 */
public final class AtomicFiles {

	private AtomicFiles() {
	}

	/**
	 * The temporary sibling to write before publishing {@code target}.
	 *
	 * <p>
	 * The marker goes <strong>before</strong> the extension - {@code clip.part.mp4}, not {@code clip.mp4.part}. That is not cosmetic: ffmpeg chooses
	 * its output muxer from the file name's extension, and writing to {@code .mp4.part} fails with <em>"Unable to choose an output format"</em>.
	 * Keeping the real extension last means the same helper serves every caller.
	 * </p>
	 *
	 * @param target
	 *            the final destination
	 * @return the temporary path to write first
	 */
	public static Path partFor(Path target) {
		String name = target.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String part = dot > 0 ? name.substring(0, dot) + ".part" + name.substring(dot) : name + ".part";
		return target.resolveSibling(part);
	}

	/**
	 * Move {@code source} onto {@code target}, replacing any existing file.
	 *
	 * <p>
	 * Falls back to a non-atomic replacing move when the filesystem cannot do it atomically - some network filesystems cannot, and a node that
	 * refused to produce anything there would be worse than one that leaves a narrow window.
	 * </p>
	 *
	 * <p>
	 * ⚠️ This <b>replaces</b> the target. A relocation must not use it to land on an occupied destination: resolve the conflict first with
	 * {@link Conflicts}, and use this only to publish a {@code .part} file onto the path that resolution chose.
	 * </p>
	 *
	 * @param source
	 *            the completed temporary file
	 * @param target
	 *            the destination path
	 * @throws IOException
	 *             when the move fails
	 */
	public static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
