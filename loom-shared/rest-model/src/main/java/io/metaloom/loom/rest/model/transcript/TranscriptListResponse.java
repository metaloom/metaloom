package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class TranscriptListResponse extends AbstractListResponse<TranscriptListResponse, TranscriptResponse> {

	@Override
	public TranscriptListResponse self() {
		return this;
	}

}
