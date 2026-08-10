package io.metaloom.loom.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

import io.metaloom.utils.hash.SHA512;

/**
 * The seam between "Loom has some bytes" and "the bytes are somewhere durable".
 *
 * <p>
 * One implementation per storage backend: {@link io.metaloom.loom.storage.fs.FilesystemBinaryStorage} for a directory,
 * {@code io.metaloom.loom.storage.s3.S3BinaryStorage} for a bucket. Which one serves a given upload is decided by the {@code asset_pool} row the
 * target library points at; see {@code spec/features/rest/REST_BINARY_HANDLING.md}.
 * </p>
 *
 * <h2>Locators, not paths</h2>
 *
 * <p>
 * Every method except {@link #store(Path, SHA512, String)} takes a <em>locator</em>: the opaque string that {@code store} returned and that
 * {@code asset_location.path} holds. It is opaque to Loom but not meaningless — each backend produces the form its own consumers already understand:
 * </p>
 *
 * <ul>
 * <li>filesystem → an absolute path, because the Cortex worker that later processes the asset opens {@code asset_location.path} directly off its own
 * filesystem. A relative key here would break every asset-scoped pipeline run.</li>
 * <li>S3 → an {@code s3://bucket/key} URI, because Cortex already resolves exactly that form
 * ({@code io.metaloom.cortex.s3.S3Uri}, {@code S3MediaMaterializer}). Storing a bare key would make the same media unprocessable.</li>
 * </ul>
 *
 * <p>
 * That is the whole reason the locator is not simply "the content-addressed key": it has to stay meaningful to a consumer that is not this interface.
 * </p>
 *
 * <h2>Content addressing</h2>
 *
 * <p>
 * {@code store} is keyed by SHA-512 and is idempotent: storing the same bytes twice is a no-op that returns the same locator. Callers therefore must
 * not assume they are the only owner of what they just stored — see {@code AssetBinaryEndpointService} for the reference-counted delete this implies.
 * </p>
 */
public interface BinaryStorage {

	/**
	 * Short identifier of the backend, for logs and for the {@code storageType} field of the library REST model.
	 *
	 * @return {@code "filesystem"} or {@code "s3"}
	 */
	String kind();

	/**
	 * Store the given local file under its content address.
	 *
	 * <p>
	 * Idempotent: when the content address already holds bytes the source is not copied again.
	 * </p>
	 *
	 * @param source
	 *            the local file to persist; typically the multipart temp file
	 * @param sha512
	 *            the SHA-512 of {@code source}, which becomes its address
	 * @param mimeType
	 *            content type to record where the backend supports it (S3), may be null
	 * @return the locator to persist in {@code asset_location.path}
	 * @throws BinaryStorageException
	 *             when the bytes could not be stored
	 */
	String store(Path source, SHA512 sha512, String mimeType);

	/**
	 * The locator {@link #store} would produce for these bytes, without storing anything.
	 *
	 * <p>
	 * Exists for {@code attachment_binary}, which is keyed by sha512sum and records only its pool — there is no column holding a locator, so the
	 * download path has to re-derive one. Deriving rather than storing is only sound because the layout is content-addressed and
	 * {@link StorageKeys#contentKey} is the single definition of it.
	 * </p>
	 *
	 * @param sha512
	 *            content hash
	 * @return the locator these bytes would have in this backend
	 */
	String locatorFor(SHA512 sha512);

	/**
	 * @param locator
	 *            a locator previously returned by {@link #store}
	 * @return whether the bytes are still present
	 */
	boolean exists(String locator);

	/**
	 * @param locator
	 *            a locator previously returned by {@link #store}
	 * @return the size in bytes, or -1 when unknown or absent
	 */
	long size(String locator);

	/**
	 * Open a stream over a byte range.
	 *
	 * @param locator
	 *            a locator previously returned by {@link #store}
	 * @param offset
	 *            first byte to read, 0-based
	 * @param length
	 *            number of bytes to read, or -1 for "to the end"
	 * @return the stream; the caller closes it
	 * @throws BinaryStorageException
	 *             when the object is missing or unreadable
	 */
	InputStream read(String locator, long offset, long length);

	/**
	 * The local file behind a locator, when there is one.
	 *
	 * <p>
	 * Exists so the download route can use Vert.x {@code sendFile} (zero-copy, and the only sensible way to serve a multi-gigabyte video) instead of
	 * pumping {@link #read} through the event loop. Object stores return empty and get the streaming path.
	 * </p>
	 *
	 * @param locator
	 *            a locator previously returned by {@link #store}
	 * @return the local path, or empty when the backend is not a filesystem
	 */
	Optional<Path> localPath(String locator);

	/**
	 * Remove the bytes. Removing something that is already gone is not an error — the caller has just established that nothing references it, and a
	 * missing file is the state it wanted.
	 *
	 * @param locator
	 *            a locator previously returned by {@link #store}
	 */
	void delete(String locator);

	/**
	 * Usable space, for the pre-upload capacity check.
	 *
	 * @return free bytes, or null when the backend cannot say (object stores have no meaningful answer)
	 */
	Long freeSpace();

	/**
	 * Total capacity, for the "how full is it" reading of the storage report.
	 *
	 * <p>
	 * Exists because {@link #freeSpace()} alone cannot be turned into a percentage, and a bare byte count is not what an operator reads a capacity
	 * dashboard for: 40 GB free is comfortable on a laptop volume and an emergency on a media archive. Reported alongside the free figure so the
	 * consumer can compute the fraction rather than guess at a denominator.
	 * </p>
	 *
	 * @return total bytes, or null when the backend cannot say — the same "no meaningful answer" object stores give for {@link #freeSpace()}
	 */
	Long totalSpace();

	/**
	 * @return a human-readable description of where this storage points, safe to log (never contains credentials)
	 */
	String describe();
}
