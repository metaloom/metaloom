package io.metaloom.loom.storage.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.loom.storage.BinaryStorageException;
import io.metaloom.loom.storage.StorageKeys;
import io.metaloom.utils.hash.SHA512;

/**
 * {@link BinaryStorage} over a directory.
 *
 * <p>
 * This is the behaviour {@code AssetUploadEndpointService.persist} had inline before pools existed, lifted out unchanged so that an installation with
 * no pool configured keeps producing byte-identical layouts and byte-identical {@code asset_location.path} values. That compatibility is the reason
 * the locator is the full path rather than the relative key: existing rows hold full paths, and the Cortex worker opens them directly.
 * </p>
 */
public class FilesystemBinaryStorage implements BinaryStorage {

	private static final Logger log = LoggerFactory.getLogger(FilesystemBinaryStorage.class);

	public static final String KIND = "filesystem";

	private final Path baseDir;

	/**
	 * @param baseDir
	 *            directory under which the content-addressed tree is created; created on demand
	 */
	public FilesystemBinaryStorage(String baseDir) {
		if (baseDir == null || baseDir.isBlank()) {
			throw new IllegalArgumentException("A filesystem storage needs a base directory");
		}
		this.baseDir = Paths.get(baseDir);
	}

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	public String locatorFor(SHA512 sha512) {
		return baseDir.resolve(StorageKeys.contentKey(sha512)).toString();
	}

	@Override
	public String store(Path source, SHA512 sha512, String mimeType) {
		Path target = baseDir.resolve(StorageKeys.contentKey(sha512));
		try {
			Files.createDirectories(target.getParent());
			if (Files.exists(target)) {
				log.debug("Binary {} already present in {}, reusing", sha512, baseDir);
				return target.toString();
			}
			// Copy rather than move: the multipart temp directory is frequently on a different device, where
			// an atomic move fails outright. Vert.x removes the temp file when the request ends either way.
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			return target.toString();
		} catch (IOException e) {
			throw new BinaryStorageException("Could not store binary " + sha512 + " under " + baseDir, e);
		}
	}

	@Override
	public boolean exists(String locator) {
		return Files.exists(Paths.get(locator));
	}

	@Override
	public long size(String locator) {
		try {
			return Files.size(Paths.get(locator));
		} catch (IOException e) {
			return -1;
		}
	}

	@Override
	public InputStream read(String locator, long offset, long length) {
		Path path = Paths.get(locator);
		if (!Files.exists(path)) {
			throw new BinaryStorageException("No file at " + locator);
		}
		try {
			InputStream stream = Files.newInputStream(path);
			if (offset > 0) {
				long skipped = stream.skip(offset);
				if (skipped < offset) {
					stream.close();
					throw new BinaryStorageException("Could not seek to " + offset + " in " + locator);
				}
			}
			return length < 0 ? stream : new BoundedInputStream(stream, length);
		} catch (IOException e) {
			throw new BinaryStorageException("Could not read " + locator, e);
		}
	}

	@Override
	public Optional<Path> localPath(String locator) {
		return Optional.of(Paths.get(locator));
	}

	@Override
	public void delete(String locator) {
		try {
			Files.deleteIfExists(Paths.get(locator));
		} catch (IOException e) {
			throw new BinaryStorageException("Could not delete " + locator, e);
		}
	}

	@Override
	public Long freeSpace() {
		try {
			// The base directory may not exist yet on a fresh install; walk up to the first parent that does,
			// because the answer we want is "how much room does the volume have", not "does this dir exist".
			Path probe = baseDir.toAbsolutePath();
			while (probe != null && !Files.exists(probe)) {
				probe = probe.getParent();
			}
			if (probe == null) {
				return null;
			}
			FileStore store = Files.getFileStore(probe);
			return store.getUsableSpace();
		} catch (IOException e) {
			log.warn("Could not determine free space for {}", baseDir, e);
			return null;
		}
	}

	@Override
	public String describe() {
		return KIND + ":" + baseDir;
	}

	/**
	 * Caps a stream at n bytes, so a Range request does not hand the client the rest of the file.
	 */
	static final class BoundedInputStream extends InputStream {

		private final InputStream delegate;
		private long remaining;

		BoundedInputStream(InputStream delegate, long limit) {
			this.delegate = delegate;
			this.remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int value = delegate.read();
			if (value >= 0) {
				remaining--;
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int off, int len) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int read = delegate.read(buffer, off, (int) Math.min(len, remaining));
			if (read > 0) {
				remaining -= read;
			}
			return read;
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}
}
