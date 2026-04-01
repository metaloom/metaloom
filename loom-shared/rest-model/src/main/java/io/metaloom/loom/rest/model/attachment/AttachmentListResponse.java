package io.metaloom.loom.rest.model.attachment;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class AttachmentListResponse  extends AbstractListResponse<AttachmentListResponse, AttachmentResponse> {

	@Override
	public AttachmentListResponse self() {
		return this;
	}

}
