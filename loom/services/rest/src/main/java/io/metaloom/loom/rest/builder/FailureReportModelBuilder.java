package io.metaloom.loom.rest.builder;

import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.db.model.failure.FailureReport;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.failure.FailureReportListResponse;
import io.metaloom.loom.rest.model.failure.FailureReportResponse;

public interface FailureReportModelBuilder extends ModelBuilder, UserModelBuilder {

	/** Where a report's screenshot is fetched from, relative to the server root. */
	String FAILURE_REPORT_PATH = "/api/v1/failure-reports/";

	/**
	 * Build a response for one report.
	 *
	 * <p>
	 * {@code withScreenshot} is passed in rather than looked up here, because the caller knows whether it is rendering one row or a page of them: a
	 * listing answers it for the whole page in a single query, and a builder that asked per row would issue one extra statement per report.
	 * </p>
	 *
	 * @param report
	 *            the row
	 * @param withScreenshot
	 *            whether this report has a screenshot attached
	 */
	default FailureReportResponse toResponse(FailureReport report, boolean withScreenshot) {
		FailureReportResponse response = new FailureReportResponse();
		response.setUuid(report.getUuid());
		response.setAction(report.getAction());
		response.setTraceId(report.getTraceId());
		response.setHttpMethod(report.getHttpMethod());
		response.setPath(report.getPath());
		response.setStatusCode(report.getStatusCode());
		response.setErrorMessage(report.getErrorMessage());
		response.setRoute(report.getRoute());
		response.setUserAgent(report.getUserAgent());
		response.setText(report.getText());
		response.setTriageStatus(report.getTriageStatus());
		response.setHasScreenshot(withScreenshot);
		// Relative. Absolute URLs are the endpoint service's job, because only the request knows which host the
		// caller reached this server on - the same split ShareModelBuilder makes for the share URL.
		response.setScreenshotUrl(withScreenshot ? FAILURE_REPORT_PATH + report.getUuid() + "/screenshot" : null);
		setStatus(report, response);
		return response;
	}

	/**
	 * Build a page of reports.
	 *
	 * @param page
	 *            the page
	 * @param withScreenshot
	 *            the uuids in this page that have a screenshot, resolved in one query by the caller
	 */
	default FailureReportListResponse toFailureReportList(Page<FailureReport> page, Set<UUID> withScreenshot) {
		return setPage(new FailureReportListResponse(), page, report -> toResponse(report, withScreenshot.contains(report.getUuid())));
	}
}
