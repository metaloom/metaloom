package io.metaloom.loom.db.storage;

/**
 * Counts what is stored, per kind of content.
 *
 * <p>
 * A service rather than a method on {@code AttachmentDao} because the report spans {@code attachment}, {@code attachment_binary}, {@code person},
 * {@code asset_location} and {@code asset}. No single DAO owns that set, and splitting the queries across three of them would put the one thing worth
 * testing - that the numbers agree with each other - in no single place.
 * </p>
 *
 * <p>
 * The interface lives in {@code loom-db-api} for the same reason {@code DbIntegrityService} does: it is the seam the jOOQ implementation is bound
 * behind, and the REST layer must be able to depend on it without depending on jOOQ.
 * </p>
 */
public interface StorageStatsService {

	/**
	 * Compute the report.
	 *
	 * <p>
	 * Several aggregate scans over the attachment and asset tables. Blocking, and not cheap on a large installation - call it off the event loop, and
	 * do not put it behind an endpoint that something polls.
	 * </p>
	 */
	StorageReport report();
}
