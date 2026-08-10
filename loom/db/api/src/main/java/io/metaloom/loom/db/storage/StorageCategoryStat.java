package io.metaloom.loom.db.storage;

/**
 * What one category of stored content costs.
 *
 * <p>
 * Two byte figures, deliberately, because neither alone is the truth:
 * </p>
 *
 * <ul>
 * <li>{@code logicalBytes} is what the catalogue claims - add up the size of every row. It is what a user would compute by hand and what a quota
 * would be charged against, and on a face-crop-heavy install it can be several times the disk actually used.</li>
 * <li>{@code distinctBytes} is what the disk holds - each stored object counted once, however many rows point at it. Storage is content-addressed, so
 * duplicates cost nothing beyond the first copy.</li>
 * </ul>
 *
 * <p>
 * 🔴 {@code distinctBytes} is <strong>not summable across categories</strong>. One stored object can appear under two of them - copying a face crop
 * into a person's gallery deliberately shares the bytes - so adding the column up double-counts. The physical total is a separate query; see
 * {@link StorageReport#distinctBytes()}.
 * </p>
 *
 * @param category      which bucket this is
 * @param elements      how many rows are in it
 * @param logicalBytes  the sum of those rows' sizes
 * @param distinctObjects how many distinct stored objects those rows resolve to
 * @param distinctBytes the sum of those objects' sizes, each counted once
 */
public record StorageCategoryStat(
	StorageCategory category,
	long elements,
	long logicalBytes,
	long distinctObjects,
	long distinctBytes) {

	public static StorageCategoryStat empty(StorageCategory category) {
		return new StorageCategoryStat(category, 0, 0, 0, 0);
	}

	/** Bytes the content-addressed store saved by not writing the duplicates. Never negative. */
	public long savedBytes() {
		return Math.max(0, logicalBytes - distinctBytes);
	}
}
