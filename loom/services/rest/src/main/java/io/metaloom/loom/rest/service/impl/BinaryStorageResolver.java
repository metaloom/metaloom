package io.metaloom.loom.rest.service.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.loom.storage.fs.FilesystemBinaryStorage;
import io.metaloom.loom.storage.s3.S3BinaryStorage;

/**
 * Turns "which pool?" into "which {@link BinaryStorage}?".
 *
 * <p>
 * This is the only place that knows both halves of the storage decision: the {@code asset_pool} row (bucket or directory, from the database, editable
 * by an operator) and the process configuration ({@code LOOM_S3_*} credentials, {@code LOOM_STORAGE_UPLOAD_DIR}, never in the database).
 * </p>
 *
 * <h2>Caching</h2>
 *
 * <p>
 * Backends are cached per pool uuid and never evicted. An {@link software.amazon.awssdk.services.s3.S3Client} costs real time to build — credential
 * chain resolution, TLS setup — and building one per request would put that on the upload path. The trade is that editing a pool's bucket or endpoint
 * needs a restart to take effect; that is called out in {@code REST_BINARY_HANDLING.md} rather than solved with an invalidation hook nothing would
 * exercise. The map is bounded by the number of pools, which is operator-created and small.
 * </p>
 *
 * <p>
 * 🔴 Per {@code spec/CLUSTERING.md} this is per-process cached state, which is fine only while Loom stays single-writer.
 * </p>
 */
@Singleton
public class BinaryStorageResolver {

	private static final Logger log = LoggerFactory.getLogger(BinaryStorageResolver.class);

	/** Sentinel for "no pool" — {@link ConcurrentHashMap} rejects null keys. */
	private static final UUID DEFAULT_POOL = new UUID(0L, 0L);

	private final DaoCollection daos;
	private final LoomOptions options;
	private final Map<UUID, BinaryStorage> cache = new ConcurrentHashMap<>();

	/**
	 * Pool kind, memoized separately from {@link #cache}. Listing libraries asks this once per row, and a pool that is misconfigured must still
	 * report a kind rather than fail the read — so this cannot go through {@link #forPool(UUID)}, which builds a client and throws.
	 */
	private final Map<UUID, String> kindCache = new ConcurrentHashMap<>();

	@Inject
	public BinaryStorageResolver(DaoCollection daos, LoomOptions options) {
		this.daos = daos;
		this.options = options;
	}

	/**
	 * The storage a library's uploads go to.
	 *
	 * @param libraryUuid
	 *            the target library, may be null
	 * @return the backend; the local upload directory when the library is unknown or has no pool
	 */
	public BinaryStorage forLibrary(UUID libraryUuid) {
		return forPool(poolUuidOfLibrary(libraryUuid));
	}

	/**
	 * The pool a library stores into.
	 *
	 * @param libraryUuid
	 *            the library, may be null
	 * @return the pool uuid, or null for the default local storage
	 */
	public UUID poolUuidOfLibrary(UUID libraryUuid) {
		if (libraryUuid == null) {
			return null;
		}
		Library library = daos.libraryDao().load(libraryUuid);
		return library == null ? null : library.getPoolUuid();
	}

	/**
	 * The storage behind a pool.
	 *
	 * @param poolUuid
	 *            the pool, or null for the default local storage
	 * @return the backend, never null
	 * @throws LoomRestException
	 *             404 when the pool uuid does not exist, 400 when the pool row is not usable
	 */
	public BinaryStorage forPool(UUID poolUuid) {
		return cache.computeIfAbsent(poolUuid == null ? DEFAULT_POOL : poolUuid, key -> {
			if (DEFAULT_POOL.equals(key)) {
				FilesystemBinaryStorage storage = new FilesystemBinaryStorage(options.getStorage().getUploadDirectory());
				log.info("Resolved default binary storage: {}", storage.describe());
				return storage;
			}
			AssetPool pool = daos.assetPoolDao().load(key);
			if (pool == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No storage pool found for uuid " + key);
			}
			return build(pool);
		});
	}

	/**
	 * The backend kind a library writes to, for the {@code storageType} field of the REST models. Resolving this must never fail a read of a library
	 * that happens to point at a broken pool, so a pool that cannot be built reports its declared kind rather than throwing.
	 *
	 * @param poolUuid
	 *            the pool, or null
	 * @return {@code "filesystem"} or {@code "s3"}
	 */
	public String storageTypeOfPool(UUID poolUuid) {
		if (poolUuid == null) {
			return FilesystemBinaryStorage.KIND;
		}
		return kindCache.computeIfAbsent(poolUuid, key -> {
			AssetPool pool = daos.assetPoolDao().load(key);
			if (pool == null) {
				return FilesystemBinaryStorage.KIND;
			}
			return isS3(pool) ? S3BinaryStorage.KIND : FilesystemBinaryStorage.KIND;
		});
	}

	private BinaryStorage build(AssetPool pool) {
		if (isS3(pool)) {
			S3BinaryStorage storage = new S3BinaryStorage(pool.getS3Bucket(), pool.getS3Region(), pool.getS3Endpoint(), options.getS3());
			log.info("Resolved binary storage for pool '{}': {}", pool.getName(), storage.describe());
			return storage;
		}
		if (pool.getFsPath() != null && !pool.getFsPath().isBlank()) {
			FilesystemBinaryStorage storage = new FilesystemBinaryStorage(pool.getFsPath());
			log.info("Resolved binary storage for pool '{}': {}", pool.getName(), storage.describe());
			return storage;
		}
		// The V2.20 CHECK constraint makes this unreachable through SQL, but a pool built in a test
		// fixture or an in-memory DAO can still get here, and "silently wrote your bytes to the local
		// disk instead of the bucket you asked for" is the worst possible failure mode.
		throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
			"Storage pool '" + pool.getName() + "' declares neither a filesystem path nor an S3 bucket.");
	}

	private static boolean isS3(AssetPool pool) {
		return pool.getS3Bucket() != null && !pool.getS3Bucket().isBlank();
	}
}
