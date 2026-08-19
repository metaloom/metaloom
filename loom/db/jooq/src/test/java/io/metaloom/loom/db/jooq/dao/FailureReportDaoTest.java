package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.failure.FailureReport;
import io.metaloom.loom.db.model.failure.FailureReportDao;
import io.metaloom.loom.db.model.failure.FailureReportScreenshot;
import io.metaloom.loom.db.model.failure.FailureReportTriageStatus;
import io.metaloom.loom.db.model.user.User;

public class FailureReportDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<FailureReportDao, FailureReport> {

	/** A 1x1 PNG. The bytes only have to survive a round trip here; the type sniffing is the endpoint's job. */
	private static final byte[] PNG = new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03 };

	@Override
	public FailureReportDao getDao() {
		return failureReportDao();
	}

	@Override
	public FailureReport createElement(User user, int i) {
		FailureReport report = getDao().createFailureReport(user.getUuid(), "createPerson_" + i);
		report.setTraceId("trace_" + i);
		report.setStatusCode(500);
		report.setText("Report number " + i);
		return report;
	}

	@Override
	public void assertCreate(FailureReport createdElement) {
		assertEquals(FailureReportTriageStatus.NEW, createdElement.triageStatus(), "A fresh report starts in NEW");
		assertEquals(500, createdElement.getStatusCode());
		assertNotNull(createdElement.getTraceId());
	}

	@Override
	public void assertUpdate(FailureReport updatedElement) {
		assertEquals(FailureReportTriageStatus.RESOLVED, updatedElement.triageStatus());
	}

	@Override
	public void updateElement(FailureReport report) {
		report.setTriageStatus(FailureReportTriageStatus.RESOLVED);
	}

	@Test
	public void testScreenshotRoundTrip() {
		FailureReport report = storedReport();

		assertFalse(getDao().hasScreenshot(report.getUuid()), "A fresh report has no screenshot");
		assertNull(getDao().loadScreenshot(report.getUuid()));

		getDao().storeScreenshot(report.getUuid(), "image/png", 1920, 1080, PNG);

		FailureReportScreenshot screenshot = getDao().loadScreenshot(report.getUuid());
		assertNotNull(screenshot);
		assertEquals("image/png", screenshot.getMimeType());
		assertEquals(1920, screenshot.getWidth());
		assertEquals(1080, screenshot.getHeight());
		assertEquals(PNG.length, screenshot.getSize(), "The size is denormalised from the bytes at write time");
		assertArrayEquals(PNG, screenshot.getData());
		assertNotNull(screenshot.getCreated());
		assertTrue(getDao().hasScreenshot(report.getUuid()));
	}

	/**
	 * The primary key is the report uuid, so a resubmitted screenshot must replace rather than collide. A unique
	 * violation here would surface as a 500 on the one code path that exists to report 500s.
	 */
	@Test
	public void testStoringASecondScreenshotReplacesTheFirst() {
		FailureReport report = storedReport();

		getDao().storeScreenshot(report.getUuid(), "image/png", 1, 1, PNG);
		byte[] replacement = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x42 };
		getDao().storeScreenshot(report.getUuid(), "image/jpeg", 800, 600, replacement);

		FailureReportScreenshot screenshot = getDao().loadScreenshot(report.getUuid());
		assertEquals("image/jpeg", screenshot.getMimeType());
		assertEquals(800, screenshot.getWidth());
		assertArrayEquals(replacement, screenshot.getData());
	}

	/**
	 * What the inbox listing uses. One query for the whole page, and - the point of the method - no image bytes read.
	 */
	@Test
	public void testScreenshotUuidsAnswersForAWholePage() {
		FailureReport withImage = storedReport();
		FailureReport withoutImage = storedReport();
		getDao().storeScreenshot(withImage.getUuid(), "image/png", 1, 1, PNG);

		Set<UUID> found = getDao().screenshotUuids(java.util.List.of(withImage.getUuid(), withoutImage.getUuid()));
		assertEquals(Set.of(withImage.getUuid()), found);

		assertTrue(getDao().screenshotUuids(java.util.List.of()).isEmpty(), "An empty page must not issue a query");
	}

	/**
	 * {@code failure_report_screenshot} is {@code ON DELETE CASCADE}, so deleting the report takes the image with it.
	 * Without the cascade the row would be orphaned and unreachable - there is no other way to address it.
	 */
	@Test
	public void testDeletingAReportDeletesItsScreenshot() {
		FailureReport report = storedReport();
		getDao().storeScreenshot(report.getUuid(), "image/png", 1, 1, PNG);
		UUID uuid = report.getUuid();

		getDao().delete(uuid);

		assertNull(getDao().load(uuid));
		assertNull(getDao().loadScreenshot(uuid), "The screenshot must not outlive the report it belongs to");
		assertFalse(getDao().hasScreenshot(uuid));
	}

	/**
	 * The creator FK is {@code ON DELETE SET NULL}: deleting the person who reported a bug must not delete the bug.
	 */
	@Test
	public void testAReportOutlivesItsReporter() {
		User reporter = userDao().createUser(adminUser().getUuid(), "reporter_" + System.nanoTime());
		userDao().store(reporter);

		FailureReport report = getDao().createFailureReport(reporter.getUuid(), "createPerson");
		getDao().store(report);
		UUID uuid = report.getUuid();

		userDao().delete(reporter.getUuid());

		FailureReport survivor = getDao().load(uuid);
		assertNotNull(survivor, "Deleting the reporter must not delete the report");
		assertNull(survivor.getCreatorUuid(), "The reporter becomes anonymous, the finding survives");
		assertEquals("createPerson", survivor.getAction());
	}

	private FailureReport storedReport() {
		FailureReport report = getDao().createFailureReport(adminUser().getUuid(), "createPerson");
		getDao().store(report);
		return report;
	}
}
