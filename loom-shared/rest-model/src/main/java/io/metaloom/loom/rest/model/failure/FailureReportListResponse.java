package io.metaloom.loom.rest.model.failure;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class FailureReportListResponse extends AbstractListResponse<FailureReportListResponse, FailureReportResponse> {

	@Override
	public FailureReportListResponse self() {
		return this;
	}

}
