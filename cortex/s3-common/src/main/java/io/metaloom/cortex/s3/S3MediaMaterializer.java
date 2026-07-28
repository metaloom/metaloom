package io.metaloom.cortex.s3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an {@code s3://bucket/key} reference into a local file, caching the bytes on the worker
 * that asked for them.
 *
 * <p>This is what removes the shared-storage prerequisite recorded in {@code MediaRef}'s Javadoc.
 * The source node emits references rather than paths and downloads nothing; each worker that
 * later runs a node task materializes into its own cache. Two workers with no common mount can
 * therefore process the same object.</p>
 *
 * <h2>Cache layout</h2>
 *
 * <pre>
 * &lt;cacheRoot&gt;/&lt;first 4 hex of sha256(bucket/key)&gt;/&lt;sha256(bucket/key)&gt;-&lt;etag&gt;&lt;ext&gt;
 * </pre>
 *
 * <ul>
 * <li>The key's <b>extension is preserved</b>. Cortex detects media type from the path
 * ({@code LoomMediaImpl.isVideo()} delegates to {@code FilterHelper.isVideo(path())}), so an
 * object cached without its extension would be skipped by every media node.</li>
 * <li>The <b>etag is part of the file name</b>, so a modified object lands at a different path
 * and a stale copy can never be served. The etag is an opaque change token, not a content hash -
 * multipart uploads report {@code <md5-of-md5s>-<partcount>}.</li>
 * <li>The 4-hex shard mirrors the {@code *_bin} convention used by {@code ThumbnailNode},
 * {@code TtsNode} and {@code ImageGenNode}. Their helper ({@code HashUtils.segmentPath}) takes a
 * SHA-512, which is unknown before the download, so the sharding is over the key hash instead.</li>
 * </ul>
 */
public class S3MediaMaterializer {

	private static final Logger log = LoggerFactory.getLogger(S3MediaMaterializer.class);

	/** Sweep down to this fraction of the budget so eviction is not re-triggered immediately. */
	private static final double EVICTION_TARGET_RATIO = 0.9;

	private final S3ObjectStore store;
	private final Path cacheRoot;
	private final long maxObjectSize;
	private final long maxCacheBytes;

	public S3MediaMaterializer(S3ObjectStore store, Path cacheRoot, long maxObjectSize, long maxCacheBytes) {
		if (store == null) {
			throw new IllegalArgumentException("An S3 object store must be provided");
		}
		if (cacheRoot == null) {
			throw new IllegalArgumentException("A cache root directory must be provided");
		}
		this.store = store;
		this.cacheRoot = cacheRoot;
		this.maxObjectSize = maxObjectSize;
		this.maxCacheBytes = maxCacheBytes;
	}

	/**
	 * Materialize an object whose metadata is already known (e.g. straight from a listing), which
	 * avoids the HEAD round trip.
	 *
	 * @param ref the object
	 * @return the local file holding its bytes
	 * @throws IOException on transport failure, or when the object exceeds the size limit
	 */
	public Path materialize(S3ObjectRef ref) throws IOException {
		if (ref == null) {
			throw new IllegalArgumentException("An object reference must be provided");
		}
		if (maxObjectSize > 0 && ref.size() > maxObjectSize) {
			throw new IOException("Object " + ref.reference() + " is " + ref.size()
				+ " bytes which exceeds the configured maxObjectSize of " + maxObjectSize);
		}

		Path target = cachePath(ref.uri(), ref.etag());
		if (Files.isRegularFile(target)) {
			// Etag is part of the name, so an existing file is by construction the right bytes.
			touch(target);
			log.debug("Cache hit for {} at {}", ref.reference(), target);
			return target;
		}

		Files.createDirectories(target.getParent());
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		try {
			log.info("Materializing {} ({} bytes) into {}", ref.reference(), ref.size(), target);
			store.download(ref.bucket(), ref.key(), partial);
			moveIntoPlace(partial, target);
		} finally {
			Files.deleteIfExists(partial);
		}

		evictIfOverBudget();
		return target;
	}

	/**
	 * Materialize an object identified only by reference. Costs one HEAD to learn the etag, which
	 * is what keys the cache entry.
	 *
	 * @param reference an {@code s3://bucket/key} URI
	 * @return the local file holding its bytes
	 * @throws IOException when the object does not exist or cannot be fetched
	 */
	public Path materialize(String reference) throws IOException {
		S3Uri uri = S3Uri.parse(reference);
		S3ObjectRef ref = store.head(uri.bucket(), uri.key());
		if (ref == null) {
			throw new IOException("Object does not exist: " + reference);
		}
		return materialize(ref);
	}

	/**
	 * The cache location for an object. Deterministic, so a lazy resolver added later computes the
	 * same path as the code that wrote it.
	 *
	 * @param uri  the object
	 * @param etag its change token; may be null, in which case a marker is used instead
	 * @return the absolute cache path
	 */
	public Path cachePath(S3Uri uri, String etag) {
		String digest = sha256Hex(uri.bucket() + "/" + uri.key());
		String token = etag == null || etag.isBlank() ? "noetag" : sanitize(etag);
		String fileName = digest + "-" + token + uri.extension();
		return cacheRoot.resolve(digest.substring(0, 4)).resolve(fileName);
	}

	private static void moveIntoPlace(Path partial, Path target) throws IOException {
		try {
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			// Some filesystems (and bind mounts across devices) cannot move atomically. The
			// cache is content-addressed by etag, so a racing writer produces identical bytes.
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.FileAlreadyExistsException e) {
			// Another thread materialized the same object first - its bytes are ours.
			log.debug("Concurrent materialization won the race for {}", target);
		}
	}

	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException e) {
			// Only affects eviction ordering, never correctness.
			log.debug("Could not update access time of {}", file, e);
		}
	}

	/**
	 * Trim the cache back under budget, oldest first.
	 *
	 * <p>Runs after a download rather than after every access: a download already costs a network
	 * transfer, so a directory walk alongside it is cheap, and cache hits stay free. There is no
	 * cross-process coordination - two workers sharing a cache directory may each evict - which is
	 * acceptable because an evicted object is simply re-fetched.</p>
	 */
	private void evictIfOverBudget() {
		if (maxCacheBytes <= 0 || !Files.isDirectory(cacheRoot)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(cacheRoot)) {
			List<Path> files = new ArrayList<>();
			long total = 0;
			for (Path path : walk.filter(Files::isRegularFile).toList()) {
				files.add(path);
				total += sizeOf(path);
			}
			if (total <= maxCacheBytes) {
				return;
			}

			long target = (long) (maxCacheBytes * EVICTION_TARGET_RATIO);
			files.sort(Comparator.comparingLong(S3MediaMaterializer::lastModifiedOf));
			for (Path path : files) {
				if (total <= target) {
					break;
				}
				long size = sizeOf(path);
				try {
					Files.delete(path);
					total -= size;
					log.debug("Evicted {} ({} bytes) from the S3 cache", path, size);
				} catch (IOException e) {
					log.debug("Could not evict {}", path, e);
				}
			}
			log.info("Swept the S3 media cache down to {} bytes (budget {})", total, maxCacheBytes);
		} catch (IOException e) {
			log.warn("Failed to sweep the S3 media cache at {}", cacheRoot, e);
		}
	}

	private static long sizeOf(Path path) {
		try {
			return Files.size(path);
		} catch (IOException e) {
			return 0;
		}
	}

	private static long lastModifiedOf(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	/** Keep the file name safe on every filesystem; multipart etags carry a dash, which is fine. */
	private static String sanitize(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			builder.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
		}
		return builder.toString();
	}

	static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is a required algorithm on every JRE.
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	/**
	 * @return the store this materializer downloads through
	 */
	public S3ObjectStore store() {
		return store;
	}

	/**
	 * @return the cache root directory
	 */
	public Path cacheRoot() {
		return cacheRoot;
	}

	/**
	 * @return the largest object this materializer will fetch, or 0 when unbounded
	 */
	public long maxObjectSize() {
		return maxObjectSize;
	}

	/**
	 * Convenience for callers that cannot handle a checked exception (e.g. inside a media handle's
	 * {@code path()}).
	 *
	 * @param ref the object
	 * @return the local file
	 */
	public Path materializeUnchecked(S3ObjectRef ref) {
		try {
			return materialize(ref);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to materialize " + ref.reference(), e);
		}
	}
}
