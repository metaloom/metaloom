package io.metaloom.loom.db.jooq.dao.failure;

import static io.metaloom.loom.db.jooq.tables.JooqFailureReport.FAILURE_REPORT;
import static io.metaloom.loom.db.jooq.tables.JooqFailureReportScreenshot.FAILURE_REPORT_SCREENSHOT;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.failure.FailureReport;
import io.metaloom.loom.db.model.failure.FailureReportDao;
import io.metaloom.loom.db.model.failure.FailureReportScreenshot;
import io.metaloom.loom.db.model.failure.FailureReportTriageStatus;

@Singleton
public class FailureReportDaoImpl extends AbstractJooqDao<FailureReport> implements FailureReportDao {

	@Inject
	public FailureReportDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "FailureReports";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return FAILURE_REPORT;
	}

	@Override
	protected Class<? extends FailureReport> getPojoClass() {
		return FailureReportImpl.class;
	}

	@Override
	public FailureReport createFailureReport(UUID userUuid, String action) {
		FailureReport report = new FailureReportImpl();
		report.setAction(action);
		// Set here rather than left to the column default: the row is serialised into the response to the request
		// that created it, before it has ever been read back, so a null status would render as an empty triage
		// column in the dialog's own confirmation.
		report.setTriageStatus(FailureReportTriageStatus.NEW);
		setCreatorEditor(report, userUuid);
		return report;
	}

	@Override
	public void storeScreenshot(UUID reportUuid, String mimeType, Integer width, Integer height, byte[] data) {
		// An upsert, not an insert: the primary key is the report uuid, so a client that retried a submission would
		// otherwise get a unique violation surfaced as a 500 on the one code path that exists to report 500s.
		ctx().insertInto(FAILURE_REPORT_SCREENSHOT)
			.set(FAILURE_REPORT_SCREENSHOT.REPORT_UUID, reportUuid)
			.set(FAILURE_REPORT_SCREENSHOT.MIME_TYPE, mimeType)
			.set(FAILURE_REPORT_SCREENSHOT.WIDTH, width)
			.set(FAILURE_REPORT_SCREENSHOT.HEIGHT, height)
			.set(FAILURE_REPORT_SCREENSHOT.SIZE, (long) data.length)
			.set(FAILURE_REPORT_SCREENSHOT.DATA, data)
			.set(FAILURE_REPORT_SCREENSHOT.CREATED, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
			.onConflict(FAILURE_REPORT_SCREENSHOT.REPORT_UUID)
			.doUpdate()
			.set(FAILURE_REPORT_SCREENSHOT.MIME_TYPE, mimeType)
			.set(FAILURE_REPORT_SCREENSHOT.WIDTH, width)
			.set(FAILURE_REPORT_SCREENSHOT.HEIGHT, height)
			.set(FAILURE_REPORT_SCREENSHOT.SIZE, (long) data.length)
			.set(FAILURE_REPORT_SCREENSHOT.DATA, data)
			.execute();
	}

	@Override
	public FailureReportScreenshot loadScreenshot(UUID reportUuid) {
		if (reportUuid == null) {
			return null;
		}
		Record record = ctx()
			.select(FAILURE_REPORT_SCREENSHOT.fields())
			.from(FAILURE_REPORT_SCREENSHOT)
			.where(FAILURE_REPORT_SCREENSHOT.REPORT_UUID.eq(reportUuid))
			.fetchOne();
		if (record == null) {
			return null;
		}
		LocalDateTime created = record.get(FAILURE_REPORT_SCREENSHOT.CREATED);
		// Mapped by hand rather than through fetchOneInto: the pojo carries an Instant and the column a
		// LocalDateTime, and the reflective mapper's behaviour across that pair is not something a blob read
		// should depend on.
		return new FailureReportScreenshotImpl()
			.setReportUuid(record.get(FAILURE_REPORT_SCREENSHOT.REPORT_UUID))
			.setMimeType(record.get(FAILURE_REPORT_SCREENSHOT.MIME_TYPE))
			.setWidth(record.get(FAILURE_REPORT_SCREENSHOT.WIDTH))
			.setHeight(record.get(FAILURE_REPORT_SCREENSHOT.HEIGHT))
			.setSize(record.get(FAILURE_REPORT_SCREENSHOT.SIZE))
			.setData(record.get(FAILURE_REPORT_SCREENSHOT.DATA))
			.setCreated(created == null ? null : created.toInstant(ZoneOffset.UTC));
	}

	@Override
	public Set<UUID> screenshotUuids(Collection<UUID> reportUuids) {
		if (reportUuids == null || reportUuids.isEmpty()) {
			return Set.of();
		}
		// Only the key column is selected. Reading DATA here would defeat the entire reason this table is separate.
		return ctx()
			.select(FAILURE_REPORT_SCREENSHOT.REPORT_UUID)
			.from(FAILURE_REPORT_SCREENSHOT)
			.where(FAILURE_REPORT_SCREENSHOT.REPORT_UUID.in(reportUuids))
			.fetch()
			.stream()
			.map(record -> record.get(FAILURE_REPORT_SCREENSHOT.REPORT_UUID))
			.collect(Collectors.toSet());
	}

	@Override
	public boolean hasScreenshot(UUID reportUuid) {
		if (reportUuid == null) {
			return false;
		}
		// selectOne, never select(): the point of this method is to answer a boolean without reading a megabyte.
		return ctx().fetchExists(ctx()
			.selectOne()
			.from(FAILURE_REPORT_SCREENSHOT)
			.where(FAILURE_REPORT_SCREENSHOT.REPORT_UUID.eq(reportUuid)));
	}
}
