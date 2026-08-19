package io.metaloom.loom.rest.model.failure;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Move a report through triage.
 *
 * <p>
 * Status is the only editable field. Everything else on a report is a record of what happened to somebody, and a record an operator can rewrite is not
 * a record.
 * </p>
 */
public class FailureReportUpdateRequest implements RestRequestModel {

	@JsonPropertyDescription("New triage state: NEW, ACKNOWLEDGED or RESOLVED.")
	private String triageStatus;

	public String getTriageStatus() {
		return triageStatus;
	}

	public FailureReportUpdateRequest setTriageStatus(String triageStatus) {
		this.triageStatus = triageStatus;
		return this;
	}
}
