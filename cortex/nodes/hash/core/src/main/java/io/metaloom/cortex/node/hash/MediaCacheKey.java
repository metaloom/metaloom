package io.metaloom.cortex.node.hash;

import java.io.File;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Key for the hash nodes' in-heap skip caches.
 *
 * <p>
 * 🔴 Not the path alone. A path names a location, not content: a file that is rewritten in place, or
 * that was hashed while still being copied in, keeps its path and changes its bytes. Keyed on the
 * path, the cache answered with the old digest for the rest of the worker's life - and for
 * {@code sha512}, which sets the media's content identity, every later node in the graph then
 * loaded and wrote to the asset of the <em>previous</em> content. Size and modification time are
 * what a filesystem itself uses to notice a change, and both are one {@code stat} away.
 * </p>
 */
final class MediaCacheKey {

	private MediaCacheKey() {
	}

	/**
	 * @param media
	 *            the media item
	 * @return a key that changes whenever the file's size or modification time does
	 */
	static String of(LoomMedia media) {
		String path = media.absolutePath();
		File file = new File(path);
		return path + "|" + file.length() + "|" + file.lastModified();
	}
}
