package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.failure.FailureReportCreateRequest;
import io.metaloom.loom.rest.model.failure.FailureReportListResponse;
import io.metaloom.loom.rest.model.failure.FailureReportResponse;
import io.metaloom.loom.rest.model.failure.FailureReportUpdateRequest;

/**
 * Problem reports submitted from the UI.
 *
 * <p>
 * {@link #createFailureReport(FailureReportCreateRequest)} needs authentication and no permission; everything else here needs a {@code *_FAILURE_REPORT}
 * grant.
 * </p>
 */
public interface FailureReportMethods {

	LoomClientRequest<FailureReportResponse> loadFailureReport(UUID reportUuid);

	/**
	 * Submit a problem report. Only {@code action} is required - a failure that produced no response is still worth reporting.
	 */
	LoomClientRequest<FailureReportResponse> createFailureReport(FailureReportCreateRequest request);

	default LoomClientRequest<FailureReportResponse> createFailureReport(String action, String text) {
		return createFailureReport(new FailureReportCreateRequest().setAction(action).setText(text));
	}

	/** Move a report through triage. The triage status is the only editable field. */
	LoomClientRequest<FailureReportResponse> updateFailureReport(UUID reportUuid, FailureReportUpdateRequest request);

	LoomClientRequest<FailureReportListResponse> listFailureReports();

	LoomClientRequest<NoResponse> deleteFailureReport(UUID reportUuid);
}
