package io.metaloom.cortex.node.relocate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * Building the destination path below a root.
 *
 * <p>
 * Shared by every filesystem destination, because "where under the root" is a separate question from "which root", and only the second one differs
 * between a folder, a pool and a library.
 * </p>
 */
public final class Layouts {

	private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

	private Layouts() {
	}

	/**
	 * Resolve where {@code media} belongs under {@code root}.
	 *
	 * @param root
	 *            the destination root
	 * @param media
	 *            the item being relocated
	 * @param layout
	 *            how to build the path below the root
	 * @param sourceRoot
	 *            the scan root that {@link Layout#MIRROR} makes paths relative to; may be null
	 * @return
	 * @throws IOException
	 */
	public static Path resolve(Path root, LoomMedia media, Layout layout, Path sourceRoot) throws IOException {
		Path file = media.file().toPath().toAbsolutePath().normalize();
		String name = file.getFileName().toString();

		switch (layout) {
		case CONTENT:
			return root.resolve(contentKey(media));
		case DATE:
			String yearMonth = YEAR_MONTH.format(Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.systemDefault()));
			return root.resolve(yearMonth).resolve(name);
		case MIRROR:
			Path relative = relativize(file, sourceRoot);
			// No source root means nothing to be relative to. Mirroring the absolute path instead would
			// recreate the whole filesystem tree under the target, so fall back to the flat layout.
			return relative == null ? root.resolve(name) : root.resolve(relative);
		case FLAT:
		default:
			return root.resolve(name);
		}
	}

	private static Path relativize(Path file, Path sourceRoot) {
		if (sourceRoot == null) {
			return null;
		}
		Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
		if (!file.startsWith(normalizedRoot)) {
			return null;
		}
		return normalizedRoot.relativize(file);
	}

	/**
	 * Whether the content-addressed destination is already occupied by these very bytes.
	 *
	 * <p>
	 * 🔴 Only meaningful for {@link Layout#CONTENT}, and it is what keeps a second copy of the same content out of a pool. The path there <em>is</em>
	 * the SHA-512, so a file of the same length already sitting at it is the same file - the identical argument {@code S3BucketDestination} makes with a
	 * {@code head()}. Without this check the conflict policy takes over and the duplicate lands at {@code <sha512>_1}, which is not a content key:
	 * Loom derives the location it reads from the hash, so it would never look there, and with
	 * {@link SourcePolicy#DELETE_AFTER_VERIFY} the original would have been deleted to put it out of reach.
	 * </p>
	 *
	 * @param target
	 *            the content-addressed destination path
	 * @param media
	 *            the item being relocated
	 * @return true when the destination already holds this content
	 */
	public static boolean holdsSameContent(Path target, LoomMedia media) throws IOException {
		return Files.isRegularFile(target) && Files.size(target) == media.size();
	}

	/**
	 * The content-addressed key Loom's own storage backends use: {@code ab/cd/ef/<sha512>}, no extension.
	 *
	 * <p>
	 * ⚠️ Deliberately <b>not</b> {@code HashUtils.segmentPath}, which shards on a single four-character prefix. That helper addresses the worker's
	 * local artifact caches; this one has to agree with {@code StorageKeys.contentKey} on the server, or Loom looks for the bytes somewhere the node
	 * did not put them.
	 * </p>
	 */
	public static String contentKey(LoomMedia media) {
		SHA512 sha = media.getSHA512();
		if (sha == null) {
			throw new IllegalStateException(
				"The content-addressed layout needs a SHA-512 for " + media.absolutePath() + "; wire a hash node before this one");
		}
		String hex = sha.toString().toLowerCase(java.util.Locale.ROOT);
		return hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex.substring(4, 6) + "/" + hex;
	}
}
