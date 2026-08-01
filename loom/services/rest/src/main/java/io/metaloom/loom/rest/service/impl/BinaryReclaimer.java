package io.metaloom.loom.rest.service.impl;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.storage.BinaryStorage;

/**
 * Unlinks stored bytes once the last {@code asset_location} row stops pointing at them.
 *
 * <h2>Why this cannot be an unconditional delete</h2>
 *
 * <p>
 * Storage is content-addressed: two assets with identical bytes — the same file imported into two libraries, a re-upload of something already
 * present — share one object. Deleting it because <em>one</em> row went away would blank the download and the preview of every other asset that
 * deduplicated onto it, and the failure would surface later and somewhere else. So the byte-level delete is reference-counted on
 * {@code (pool_uuid, path)}, and only fires at zero.
 * </p>
 *
 * <h2>Why a failure here is not a failure of the request</h2>
 *
 * <p>
 * The row is already gone when this runs. A leaked object costs disk; propagating the error would leave the caller believing the delete failed when
 * the part they asked for succeeded, and a retry would 404. So this logs and returns — the same trade an object-store lifecycle rule makes.
 * </p>
 *
 * <p>
 * 🔴 Ordering matters: call this <em>after</em> the row has been deleted or re-pointed, never before. Counting while the row still exists always
 * returns at least one and nothing is ever reclaimed.
 * </p>
 */
final class BinaryReclaimer {

	private static final Logger log = LoggerFactory.getLogger(BinaryReclaimer.class);

	private BinaryReclaimer() {
	}

	/**
	 * Delete the bytes when no {@code asset_location} row references them any more.
	 *
	 * @param daos
	 *            DAO collection used for the reference count
	 * @param resolver
	 *            resolves the pool to its backend
	 * @param poolUuid
	 *            pool the bytes live in, or null for the local upload directory
	 * @param locator
	 *            the locator that was stored in {@code asset_location.path}
	 */
	static void reclaim(DaoCollection daos, BinaryStorageResolver resolver, UUID poolUuid, String locator) {
		if (locator == null || locator.isBlank()) {
			return;
		}
		try {
			long remaining = daos.assetBinaryDao().countByPoolAndPath(poolUuid, locator);
			if (remaining > 0) {
				log.debug("Keeping {}: still referenced by {} binaries", locator, remaining);
				return;
			}
			BinaryStorage storage = resolver.forPool(poolUuid);
			storage.delete(locator);
			log.info("Reclaimed unreferenced binary {} from {}", locator, storage.describe());
		} catch (Exception e) {
			log.error("Could not reclaim binary {} (pool {}). The row is gone; the bytes are leaked.", locator, poolUuid, e);
		}
	}
}
