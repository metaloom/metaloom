package io.metaloom.cortex.cloud;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
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
 * Turns a cloud-drive reference into a local file, caching the bytes on the worker that asked for
 * them.
 *
 * <p>The direct counterpart of {@code S3MediaMaterializer}, and it removes the same prerequisite:
 * the source node emits references and downloads nothing, and whichever worker later runs a node
 * task materializes into its own cache. Two workers sharing no mount can process the same file.</p>
 *
 * <h2>Cache layout</h2>
 *
 * <pre>
 * &lt;cacheRoot&gt;/&lt;first 4 hex of sha256(provider/drive/file)&gt;/&lt;sha256&gt;-&lt;changeToken&gt;&lt;ext&gt;
 * </pre>
 *
 * <ul>
 * <li>The <b>extension is preserved</b>, taken from the reference's name segment - and for an
 * exported Google document from the export MIME type instead, since the original name has none.
 * Cortex detects media type from the path, so a cached file without its extension is invisible to
 * every media node.</li>
 * <li>The <b>change token is part of the file name</b>, so a modified file lands at a new path and
 * a stale copy can never be served. It is an opaque token, not a content hash.</li>
 * <li>The 4-hex shard mirrors the {@code *_bin} convention the thumbnail, tts and imagegen nodes
 * use.</li>
 * </ul>
 */
public class CloudMediaMaterializer {

	private static final Logger log = LoggerFactory.getLogger(CloudMediaMaterializer.class);

	/** Sweep down to this fraction of the budget so eviction is not re-triggered immediately. */
	private static final double EVICTION_TARGET_RATIO = 0.9;

	private final CloudFileStore store;
	private final Path cacheRoot;
	private final long maxObjectSize;
	private final long maxCacheBytes;

	public CloudMediaMaterializer(CloudFileStore store, Path cacheRoot, long maxObjectSize, long maxCacheBytes) {
		if (store == null) {
			throw new IllegalArgumentException("A cloud file store must be provided");
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
	 * Materialize a file whose metadata is already known, which avoids a metadata round trip.
	 *
	 * @param ref the file
	 * @return the local file holding its bytes
	 * @throws IOException on transport failure, or when the file exceeds the size limit
	 */
	public Path materialize(CloudFileRef ref) throws IOException {
		if (ref == null) {
			throw new IllegalArgumentException("A file reference must be provided");
		}
		if (maxObjectSize > 0 && ref.size() > maxObjectSize) {
			throw new IOException("File " + ref.reference() + " is " + ref.size()
				+ " bytes which exceeds the configured maxObjectSize of " + maxObjectSize);
		}

		Path target = cachePath(ref);
		if (Files.isRegularFile(target)) {
			// The change token is part of the name, so an existing file is by construction the
			// right bytes.
			touch(target);
			log.debug("Cache hit for {} at {}", ref.reference(), target);
			return target;
		}

		Files.createDirectories(target.getParent());
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		try {
			log.info("Materializing {} ({} bytes) into {}", ref.reference(), ref.size(), target);
			store.download(ref, partial);
			moveIntoPlace(partial, target);
		} finally {
			Files.deleteIfExists(partial);
		}

		evictIfOverBudget();
		return target;
	}

	/**
	 * Materialize a file identified only by reference. Costs one metadata read to learn the change
	 * token, which is what keys the cache entry.
	 *
	 * @param reference a cloud media reference
	 * @return the local file holding its bytes
	 * @throws IOException when the file does not exist or cannot be fetched
	 */
	public Path materialize(String reference) throws IOException {
		CloudUri uri = CloudUri.parse(reference);
		CloudFileRef ref = store.get(uri.driveId(), uri.fileId());
		if (ref == null) {
			throw new IOException("File does not exist: " + reference);
		}
		return materialize(ref);
	}

	/**
	 * The cache location for a file. Deterministic, so the source node and the resolver on another
	 * worker agree without coordinating.
	 *
	 * @param ref the file
	 * @return the absolute cache path
	 */
	public Path cachePath(CloudFileRef ref) {
		return cachePath(ref.uri(), ref.changeToken());
	}

	/**
	 * @param uri         the file
	 * @param changeToken its change token; may be null, in which case a marker is used instead
	 * @return the absolute cache path
	 */
	public Path cachePath(CloudUri uri, String changeToken) {
		String digest = sha256Hex(uri.provider().scheme() + "/" + uri.driveId() + "/" + uri.fileId());
		String token = changeToken == null || changeToken.isBlank() ? "notoken" : sanitize(changeToken);
		String fileName = digest + "-" + token + uri.extension();
		return cacheRoot.resolve(digest.substring(0, 4)).resolve(fileName);
	}

	private static void moveIntoPlace(Path partial, Path target) throws IOException {
		try {
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			// Some filesystems cannot move atomically. The cache is keyed by change token, so a
			// racing writer produces identical bytes.
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (FileAlreadyExistsException e) {
			// Another thread materialized the same file first - its bytes are ours.
			log.debug("Concurrent materialization won the race for {}", target);
		}
	}

	private static void touch(Path file) {
		try {
			Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException e) {
			// Only affects eviction ordering, never correctness.
			log.debug("Could not update access time of {}", file, e);
		}
	}

	/**
	 * Trim the cache back under budget, oldest first. Runs after a download rather than after every
	 * access, so cache hits stay free.
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
			files.sort(Comparator.comparingLong(CloudMediaMaterializer::lastModifiedOf));
			for (Path path : files) {
				if (total <= target) {
					break;
				}
				long size = sizeOf(path);
				try {
					Files.delete(path);
					total -= size;
					log.debug("Evicted {} ({} bytes) from the cloud media cache", path, size);
				} catch (IOException e) {
					log.debug("Could not evict {}", path, e);
				}
			}
			log.info("Swept the cloud media cache down to {} bytes (budget {})", total, maxCacheBytes);
		} catch (IOException e) {
			log.warn("Failed to sweep the cloud media cache at {}", cacheRoot, e);
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

	/** Keep the file name safe on every filesystem. */
	private static String sanitize(String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			builder.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
		}
		// A cTag is long and a whole eTag longer still; the digest already carries identity, so the
		// token only has to distinguish versions of one file.
		String sanitized = builder.toString();
		return sanitized.length() <= 48 ? sanitized : sanitized.substring(0, 48);
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
	public CloudFileStore store() {
		return store;
	}

	/**
	 * @return the cache root directory
	 */
	public Path cacheRoot() {
		return cacheRoot;
	}

	/**
	 * @return the largest file this materializer will fetch, or 0 when unbounded
	 */
	public long maxObjectSize() {
		return maxObjectSize;
	}

	/**
	 * Convenience for callers that cannot handle a checked exception, i.e. inside a media handle's
	 * {@code path()}.
	 *
	 * @param ref the file
	 * @return the local file
	 */
	public Path materializeUnchecked(CloudFileRef ref) {
		try {
			return materialize(ref);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to materialize " + ref.reference(), e);
		}
	}
}
