package io.metaloom.cortex.fs;

import java.io.File;
import java.nio.file.Path;

/**
 * Whether a file sits inside a folder.
 *
 * <p>
 * 🔴 This exists because the obvious implementation is wrong. {@code FingerprintDedupApplyNode.isInFolder} normalised both sides and then compared
 * them with {@link String#startsWith(String)}, which reports that {@code /data/dups-old/clip.mp4} is inside {@code /data/dups}: a string prefix is not
 * a path prefix. For a node that decides whether a file has already been relocated, that answer means silently skipping a move - or performing one
 * that was already done.
 * </p>
 *
 * <p>
 * {@link Path#startsWith(Path)} compares element by element, so {@code dups-old} and {@code dups} are simply different names.
 * </p>
 */
public final class PathContainment {

	private PathContainment() {
	}

	/**
	 * Return whether {@code file} is {@code folder} itself or lives somewhere beneath it.
	 *
	 * <p>
	 * Both sides are made absolute and normalised first, so {@code ../} segments and relative inputs compare correctly. Neither side has to exist:
	 * this is a question about names, and a move node asks it before creating the destination.
	 * </p>
	 *
	 * @param file
	 *            may be null, in which case the answer is false
	 * @param folder
	 *            may be null, in which case the answer is false
	 * @return
	 */
	public static boolean isInside(Path file, Path folder) {
		if (file == null || folder == null) {
			return false;
		}
		Path normalizedFile = file.toAbsolutePath().normalize();
		Path normalizedFolder = folder.toAbsolutePath().normalize();
		return normalizedFile.startsWith(normalizedFolder);
	}

	public static boolean isInside(File file, Path folder) {
		return file != null && isInside(file.toPath(), folder);
	}
}
