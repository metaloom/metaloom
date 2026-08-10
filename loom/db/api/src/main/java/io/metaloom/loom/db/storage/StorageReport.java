package io.metaloom.loom.db.storage;

import java.util.List;
import java.util.UUID;

/**
 * What the database says is stored, from the catalogue's point of view.
 *
 * <p>
 * Purely a read of rows. Nothing here has touched a filesystem or a bucket - free space and watermarks come from the storage backends and are joined
 * on at the REST layer, because {@code loom-db-jooq} has no business knowing what a pool resolves to.
 * </p>
 *
 * @param categories     one entry per {@link StorageCategory}, always all of them; a category with nothing in it reports zeros rather than being
 *                       omitted, so an empty row is distinguishable from a query that broke
 * @param perPool        how many bytes each storage pool holds, keyed by pool uuid with {@code null} for the default local storage
 * @param objects        how many distinct stored objects exist in total
 * @param distinctBytes  how many bytes those objects occupy - the real physical figure, and <strong>not</strong> the sum of the categories'
 *                       {@code distinctBytes} (see {@link StorageCategoryStat})
 * @param orphanObjects  stored attachment objects no attachment row references any more
 * @param orphanBytes    what those orphans occupy
 */
public record StorageReport(
	List<StorageCategoryStat> categories,
	List<StoragePoolStat> perPool,
	long objects,
	long distinctBytes,
	long orphanObjects,
	long orphanBytes) {

	/**
	 * The bytes one pool holds.
	 *
	 * @param poolUuid       the pool, or null for the deployment's default local storage
	 * @param objects        distinct stored objects in it
	 * @param bytes          what they occupy
	 */
	public record StoragePoolStat(UUID poolUuid, long objects, long bytes) {
	}
}
