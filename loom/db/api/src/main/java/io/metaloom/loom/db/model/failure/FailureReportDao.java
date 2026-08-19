package io.metaloom.loom.db.model.failure;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

/**
 * Reads and writes {@link FailureReport} rows and their optional screenshots.
 *
 * <p>
 * The screenshot is addressed separately from the report on purpose - see {@link FailureReportScreenshot}. Nothing here loads one implicitly, so a
 * listing can never accidentally drag megabytes of image data behind it.
 * </p>
 */
public interface FailureReportDao extends CRUDDao<FailureReport> {

	default FailureReport createFailureReport(User user, String action) {
		return createFailureReport(user == null ? null : user.getUuid(), action);
	}

	/**
	 * Build an unsaved report in state {@code NEW}. Audit columns are filled from {@code userUuid}, which may be null.
	 *
	 * @param userUuid
	 *            who is reporting, or null when the reporter could not be resolved
	 * @param action
	 *            what they were doing, in the client's vocabulary
	 */
	FailureReport createFailureReport(UUID userUuid, String action);

	/**
	 * Attach a screenshot to a report, replacing any it already has.
	 *
	 * <p>
	 * An upsert rather than an insert because the primary key is the report uuid: a client that retried a submission should not get a constraint
	 * violation surfaced as a 500.
	 * </p>
	 *
	 * @param reportUuid
	 *            the report to attach to
	 * @param mimeType
	 *            image type, already allowlisted by the caller
	 * @param width
	 *            pixel width, or null
	 * @param height
	 *            pixel height, or null
	 * @param data
	 *            the image bytes, already size-checked by the caller
	 */
	void storeScreenshot(UUID reportUuid, String mimeType, Integer width, Integer height, byte[] data);

	/**
	 * Load a report's screenshot including its bytes, or null when it has none.
	 */
	FailureReportScreenshot loadScreenshot(UUID reportUuid);

	/**
	 * Which of the given reports have a screenshot, in one query.
	 *
	 * <p>
	 * What the listing route uses. {@link #hasScreenshot(UUID)} per row would issue one extra statement per report on
	 * a page, for a boolean.
	 * </p>
	 *
	 * @param reportUuids
	 *            the reports on the page; an empty collection returns an empty set without querying
	 */
	Set<UUID> screenshotUuids(Collection<UUID> reportUuids);

	/**
	 * Whether a report has a screenshot, without reading it.
	 *
	 * <p>
	 * What the list and load routes use to set {@code hasScreenshot} on the response. Reading the blob to answer a boolean is the mistake this method
	 * exists to prevent.
	 * </p>
	 */
	boolean hasScreenshot(UUID reportUuid);
}
