package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_POOL;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET_BINARY;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetS3Meta;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.storage.BinaryStorage;
import io.metaloom.loom.storage.s3.S3Locator;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;

@Singleton
public class AssetBinaryEndpointService extends AbstractCRUDEndpointService<AssetBinaryDao, AssetBinary> {

	private static final Logger log = LoggerFactory.getLogger(AssetBinaryEndpointService.class);

	/** Vert.x {@code HttpHeaders} has no RANGE constant, only the response-side ones. */
	private static final String RANGE_HEADER = "Range";

	/** SQLSTATE for a unique-constraint violation. Postgres reports {@code asset_location_unique_library_path} with this code. */
	private static final String UNIQUE_VIOLATION = "23505";

	private final BinaryStorageResolver storageResolver;

	@Inject
	public AssetBinaryEndpointService(AssetBinaryDao assetLocationDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, BinaryStorageResolver storageResolver) {
		super(assetLocationDao, daos, modelBuilder, validator);
		this.storageResolver = storageResolver;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		// Capture where the bytes were before the row goes, then reclaim them if nothing else points there.
		AssetBinary binary = dao().load(uuid);
		UUID poolUuid = binary == null ? null : binary.getPoolUuid();
		String locator = binary == null ? null : binary.getPath();
		delete(lrc, DELETE_ASSET_BINARY, uuid);
		BinaryReclaimer.reclaim(daos(), storageResolver, poolUuid, locator);
	}

	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ASSET_BINARY, () -> {
			AssetBinaryCreateRequest request = lrc.requestBody(AssetBinaryCreateRequest.class);
			return createFrom(lrc, request, request.getAssetUuid());
		}, modelBuilder::toResponse);
	}

	public void createForAsset(LoomRoutingContext lrc, UUID assetUuid) {
		create(lrc, CREATE_ASSET_BINARY, () -> {
			AssetBinaryCreateRequest request = lrc.requestBody(AssetBinaryCreateRequest.class);
			return createFrom(lrc, request, assetUuid);
		}, modelBuilder::toResponse);
	}

	/**
	 * Register a binary record pointing at bytes that already exist, in either backend.
	 *
	 * <p>
	 * No bytes move and the target is not verified to exist — that is the point of this route as opposed to {@code /binary/data}. It is how a scanner
	 * or an external process tells Loom "this content is already at this location".
	 * </p>
	 */
	private AssetBinary createFrom(LoomRoutingContext lrc, AssetBinaryCreateRequest request, UUID assetUuid) {
		UUID creatorUuid = lrc.userUuid();
		UUID libraryUuid = request.getLibraryUuid();
		UUID poolUuid = request.getPoolUuid() != null ? request.getPoolUuid() : storageResolver.poolUuidOfLibrary(libraryUuid);

		String locator;
		if (request.getS3() != null) {
			locator = s3Locator(request.getS3(), poolUuid);
		} else if (request.getFilesystem() != null) {
			locator = request.getFilesystem().getPath();
		} else {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"Provide either a 'filesystem' path or an 's3' location for the binary.");
		}

		AssetBinary binary = dao().createAssetBinary(locator, assetUuid, creatorUuid, libraryUuid);
		binary.setPoolUuid(poolUuid);
		update(request::getMeta, binary::setMeta);
		return binary;
	}

	/**
	 * Update a binary record: its locator, and since this revision also the library and pool it belongs to.
	 *
	 * <p>
	 * Re-pointing an existing row is what makes a relocation expressible. Before {@code libraryUuid}/{@code poolUuid} were accepted here, moving bytes
	 * into another library or storage pool could only be modelled as create-new plus delete-old, which races the {@code (library_uuid, path)} unique
	 * key and risks {@link BinaryReclaimer} unlinking bytes the surviving row still refers to.
	 * </p>
	 *
	 * <p>
	 * Naming {@code poolUuid} explicitly is an operator action - it asserts which storage backend holds the bytes rather than deriving it - so it
	 * additionally requires {@link io.metaloom.loom.db.model.perm.Permission#READ_ASSET_POOL}, mirroring the rule the upload route already applies.
	 * </p>
	 */
	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		// Read before the permission check so the required permission set can depend on the body,
		// the same shape AssetUploadEndpointService.upload uses for its pool override.
		AssetBinaryUpdateRequest request = lrc.requestBody(AssetBinaryUpdateRequest.class);
		Permission[] required = request.getPoolUuid() == null
			? new Permission[] { UPDATE_ASSET_BINARY }
			: new Permission[] { UPDATE_ASSET_BINARY, READ_ASSET_POOL };

		checkPerms(lrc, () -> {
			UUID userUuid = lrc.userUuid();

			AssetBinary binary = dao().load(uuid);
			if (binary == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Asset binary not found " + uuid);
			}

			// Library and pool are applied BEFORE the locator branch: s3Locator resolves an omitted bucket
			// from the pool, so a move into a new S3 pool has to consult the new pool rather than the old one.
			if (request.getLibraryUuid() != null) {
				binary.setLibraryUuid(requireLibrary(request.getLibraryUuid()));
			}
			binary.setPoolUuid(resolvePoolUuid(request, binary));

			if (request.getS3() != null) {
				binary.setPath(s3Locator(request.getS3(), binary.getPoolUuid()));
			} else if (request.getFilesystem() != null) {
				update(request.getFilesystem()::getPath, binary::setPath);
			}
			update(request::getMeta, binary::setMeta);
			setEditor(binary, userUuid);

			try {
				dao().update(binary);
			} catch (DataAccessException e) {
				// asset_location_unique_library_path (V2.48). Two binaries landing on the same path in the same
				// library is a caller conflict, not an internal failure, so it must not surface as a driver 500.
				if (isUniqueViolation(e)) {
					throw new LoomRestException(409, LoomRestErrorCode.BAD_REQUEST,
						"A binary already exists at path " + binary.getPath() + " in library " + binary.getLibraryUuid());
				}
				throw e;
			}
			lrc.send(modelBuilder.toResponse(binary), 200);
		}, required);
	}

	/**
	 * Work out which pool the row should carry after the update.
	 *
	 * <p>
	 * An explicit {@code poolUuid} wins. Naming only a library adopts that library's pool, which is what "move this binary into that library" almost
	 * always means. Naming neither leaves the row's pool alone rather than deriving it, because the bytes have not moved.
	 * </p>
	 */
	private UUID resolvePoolUuid(AssetBinaryUpdateRequest request, AssetBinary binary) {
		if (request.getPoolUuid() != null) {
			return requirePool(request.getPoolUuid());
		}
		if (request.getLibraryUuid() != null) {
			return storageResolver.poolUuidOfLibrary(request.getLibraryUuid());
		}
		return binary.getPoolUuid();
	}

	private UUID requireLibrary(UUID libraryUuid) {
		if (daos().libraryDao().load(libraryUuid) == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No library found for uuid " + libraryUuid);
		}
		return libraryUuid;
	}

	private UUID requirePool(UUID poolUuid) {
		if (daos().assetPoolDao().load(poolUuid) == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No storage pool found for uuid " + poolUuid);
		}
		return poolUuid;
	}

	private static boolean isUniqueViolation(DataAccessException e) {
		Throwable cause = e;
		while (cause != null) {
			if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	/**
	 * Build the {@code s3://bucket/key} locator from an {@link AssetS3Meta}.
	 *
	 * <p>
	 * The bucket may be omitted when the pool already names one — the common case, since the pool is what makes the library S3-backed. Storing the
	 * bucket inside the locator rather than relying on the pool at read time is what lets a pool be re-pointed without orphaning old rows.
	 * </p>
	 */
	private String s3Locator(AssetS3Meta s3, UUID poolUuid) {
		if (s3.getObjectPath() == null || s3.getObjectPath().isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "An S3 binary needs an 'objectPath'.");
		}
		String bucket = s3.getBucket();
		if (bucket == null || bucket.isBlank()) {
			bucket = bucketOfPool(poolUuid);
		}
		if (bucket == null || bucket.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"An S3 binary needs a 'bucket', or a library/pool that names one.");
		}
		try {
			return new S3Locator(bucket, s3.getObjectPath()).toReference();
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, e.getMessage());
		}
	}

	private String bucketOfPool(UUID poolUuid) {
		if (poolUuid == null) {
			return null;
		}
		var pool = daos().assetPoolDao().load(poolUuid);
		return pool == null ? null : pool.getS3Bucket();
	}

	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ASSET_BINARY, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ASSET_BINARY, modelBuilder::toBinaryList);
	}

	/**
	 * The asset's primary binary. See {@link AssetBinaryDao#loadPrimaryByAssetUuid(UUID)} for why "primary" and not "the".
	 */
	public void loadByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		load(lrc, READ_ASSET_BINARY, () -> {
			return dao().loadPrimaryByAssetUuid(assetUuid);
		}, modelBuilder::toResponse);
	}

	/**
	 * Every binary of an asset — one per library it was imported into.
	 */
	public void listByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET_BINARY, () -> {
			List<AssetBinary> binaries = dao().loadAllByAssetUuid(assetUuid);
			AssetBinaryListResponse response = modelBuilder.toBinaryList(binaries);
			lrc.send(response);
		});
	}

	/**
	 * Stream the raw bytes of an asset's primary binary, from whichever backend holds them.
	 *
	 * <p>
	 * Honours a single-range {@code Range: bytes=} header and answers 206 with {@code Content-Range}, which is what lets a browser seek in a video
	 * instead of refetching it from byte 0 on every scrub. Multi-range requests are answered with the whole entity (200), which is legal: RFC 9110
	 * permits a server to ignore a Range it does not wish to satisfy.
	 * </p>
	 *
	 * <p>
	 * Filesystem-backed binaries take a {@code sendFile} fast path; object stores are pumped through as a stream.
	 * </p>
	 */
	public void downloadByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET_BINARY, () -> {
			AssetBinary binary = dao().loadPrimaryByAssetUuid(assetUuid);
			if (binary == null || binary.getPath() == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No binary found for asset.");
			}
			BinaryStorage storage = storageResolver.forPool(binary.getPoolUuid());
			String locator = binary.getPath();
			if (!storage.exists(locator)) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Binary file is missing in " + storage.describe() + ".");
			}

			long total = storage.size(locator);
			String mimeType = binary.getMimeType() != null ? binary.getMimeType() : "application/octet-stream";
			String fileName = fileNameOf(locator);

			HttpServerResponse response = lrc.routingContext().response();
			response.putHeader(HttpHeaders.CONTENT_TYPE, mimeType);
			response.putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
			// Advertise range support even on the full-entity response, otherwise clients never ask.
			response.putHeader(HttpHeaders.ACCEPT_RANGES, "bytes");

			ByteRange range = ByteRange.parse(lrc.routingContext().request().getHeader(RANGE_HEADER), total);
			if (range != null && range.unsatisfiable()) {
				response.setStatusCode(416).putHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + total).end();
				return;
			}
			if (range != null) {
				response.setStatusCode(206);
				response.putHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.start() + "-" + range.end() + "/" + total);
				response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(range.length()));
			} else if (total >= 0) {
				response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(total));
			}

			long offset = range == null ? 0 : range.start();
			long length = range == null ? -1 : range.length();

			Optional<Path> local = storage.localPath(locator);
			if (local.isPresent()) {
				// Zero-copy. sendFile sets Content-Length itself for the ranged form.
				if (range == null) {
					response.sendFile(local.get().toString());
				} else {
					response.sendFile(local.get().toString(), offset, length);
				}
				return;
			}
			streamTo(response, storage, locator, offset, length);
		});
	}

	/**
	 * Pump an object-store stream into the response.
	 *
	 * <p>
	 * Chunked rather than "read it all into a Buffer": these are media files, and buffering a multi-gigabyte object to serve it would trade the
	 * client's patience for the server's heap.
	 * </p>
	 */
	private void streamTo(HttpServerResponse response, BinaryStorage storage, String locator, long offset, long length) {
		if (!response.headers().contains(HttpHeaders.CONTENT_LENGTH)) {
			// Vert.x refuses a write with neither a Content-Length nor chunked encoding. Reachable when
			// the backend could not report a size, in which case there is nothing to declare up front.
			response.setChunked(true);
		}
		try (InputStream in = storage.read(locator, offset, length)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) > 0) {
				response.write(io.vertx.core.buffer.Buffer.buffer(java.util.Arrays.copyOf(buffer, read)));
			}
			response.end();
		} catch (Exception e) {
			log.error("Failed to stream {} from {}", locator, storage.describe(), e);
			if (!response.headWritten()) {
				throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Could not read the binary.");
			}
			response.reset();
		}
	}

	/**
	 * The last path segment of a locator, for the download filename. Works for both {@code /var/lib/…/abc} and {@code s3://bucket/ab/cd/ef/abc}.
	 */
	private static String fileNameOf(String locator) {
		int slash = locator.lastIndexOf('/');
		if (slash < 0) {
			slash = locator.lastIndexOf('\\');
		}
		String name = slash < 0 ? locator : locator.substring(slash + 1);
		return name.isBlank() ? "binary" : name;
	}

	public void createForAssetLegacy(LoomRoutingContext lrc, UUID assetUuid) {
		createForAsset(lrc, assetUuid);
	}

	/**
	 * Delete every binary of an asset, reclaiming any bytes that nothing else references.
	 */
	public void deleteByAssetUuid(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, DELETE_ASSET_BINARY, () -> {
			List<AssetBinary> binaries = dao().loadAllByAssetUuid(assetUuid);
			if (binaries.isEmpty()) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No binary found for asset.");
			}
			dao().deleteByAssetUuid(assetUuid);
			for (AssetBinary binary : binaries) {
				BinaryReclaimer.reclaim(daos(), storageResolver, binary.getPoolUuid(), binary.getPath());
			}
			lrc.sendNoContent();
		});
	}

	/**
	 * One satisfiable {@code Range: bytes=first-last} request.
	 *
	 * @param start
	 *            first byte, inclusive
	 * @param end
	 *            last byte, inclusive
	 * @param unsatisfiable
	 *            whether the range falls outside the entity, which is answered with 416
	 */
	record ByteRange(long start, long end, boolean unsatisfiable) {

		long length() {
			return end - start + 1;
		}

		/**
		 * Parse a Range header.
		 *
		 * @param header
		 *            the raw header value, may be null
		 * @param total
		 *            entity size, or negative when unknown
		 * @return the range, or null when the whole entity should be sent
		 */
		static ByteRange parse(String header, long total) {
			if (header == null || total < 0) {
				return null;
			}
			String value = header.trim();
			if (!value.startsWith("bytes=")) {
				return null;
			}
			String spec = value.substring("bytes=".length()).trim();
			if (spec.indexOf(',') >= 0) {
				// Multipart/byteranges is not worth implementing for a media server: no browser video
				// element asks for it. Answering with the full entity is a permitted response.
				return null;
			}
			int dash = spec.indexOf('-');
			if (dash < 0) {
				return null;
			}
			String fromText = spec.substring(0, dash).trim();
			String toText = spec.substring(dash + 1).trim();
			try {
				long start;
				long end;
				if (fromText.isEmpty()) {
					// "bytes=-500" — the final 500 bytes.
					long suffix = Long.parseLong(toText);
					if (suffix <= 0) {
						return new ByteRange(0, 0, true);
					}
					start = Math.max(0, total - suffix);
					end = total - 1;
				} else {
					start = Long.parseLong(fromText);
					end = toText.isEmpty() ? total - 1 : Long.parseLong(toText);
				}
				if (start < 0 || start >= total || end < start) {
					return new ByteRange(0, 0, true);
				}
				return new ByteRange(start, Math.min(end, total - 1), false);
			} catch (NumberFormatException e) {
				// A malformed Range is ignored rather than rejected, per RFC 9110.
				return null;
			}
		}
	}
}
