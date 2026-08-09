package io.metaloom.cortex.fs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Answering "are these two paths on the same filesystem?" <b>before</b> a move starts.
 *
 * <p>
 * 🔴 This is the check the tree did not have. {@code io.metaloom.utils.fs.FileUtils.moveFile} delegates to commons-io, which attempts a rename and, on
 * failure - exactly what happens across a mount point - falls back to a full copy followed by a delete. For a 40 GB video that turns a metadata
 * operation into a silent, unbounded, non-atomic one. The only prior art in the repository reacts to
 * {@link java.nio.file.AtomicMoveNotSupportedException} after the fact, which cannot warn beforehand or let an operator choose a policy.
 * </p>
 */
public final class FileStores {

	private FileStores() {
	}

	/**
	 * Return whether both paths resolve to the same {@link FileStore}.
	 *
	 * <p>
	 * The destination of a move usually does not exist yet, and neither does its parent on a first run, so each side is resolved by walking up to the
	 * nearest ancestor that does exist. A path with no existing ancestor at all cannot be compared and answers false, which routes the caller into its
	 * cross-device policy rather than into an optimistic atomic move that would then fail.
	 * </p>
	 *
	 * @param a
	 * @param b
	 * @return true only when both stores could be resolved and are equal
	 * @throws IOException
	 *             when the filesystem cannot be queried at all
	 */
	public static boolean sameStore(Path a, Path b) throws IOException {
		FileStore storeA = storeOf(a);
		FileStore storeB = storeOf(b);
		if (storeA == null || storeB == null) {
			return false;
		}
		return storeA.equals(storeB);
	}

	/**
	 * The {@link FileStore} holding {@code path}, or the nearest existing ancestor of it.
	 *
	 * @param path
	 * @return null when neither the path nor any ancestor exists
	 * @throws IOException
	 */
	public static FileStore storeOf(Path path) throws IOException {
		if (path == null) {
			return null;
		}
		Path candidate = path.toAbsolutePath().normalize();
		while (candidate != null && !Files.exists(candidate)) {
			candidate = candidate.getParent();
		}
		if (candidate == null) {
			return null;
		}
		return Files.getFileStore(candidate);
	}

	/**
	 * A short human-readable description of a store, for the WARN line a cross-device copy has to emit.
	 *
	 * @param store
	 * @return
	 */
	public static String describe(FileStore store) {
		if (store == null) {
			return "<unknown>";
		}
		return store.name() + " (" + store.type() + ")";
	}

	/**
	 * The description of the store holding {@code path}, resolved the same way {@link #sameStore(Path, Path)} resolves it.
	 *
	 * @param path
	 * @return
	 */
	public static String describeStoreOf(Path path) {
		try {
			return describe(storeOf(path));
		} catch (IOException e) {
			return "<unreadable>";
		}
	}
}
