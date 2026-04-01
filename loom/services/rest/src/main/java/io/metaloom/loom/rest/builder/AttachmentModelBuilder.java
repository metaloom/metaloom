package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.attachment.AttachmentListResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;

public interface AttachmentModelBuilder extends ModelBuilder, UserModelBuilder {

	default AttachmentResponse toResponse(Attachment attachment) {
		AttachmentResponse response = new AttachmentResponse();
		response.setUuid(attachment.getUuid());
		response.setFilename(attachment.getFilename());
		response.setMimeType(attachment.getMimeType());
		response.setSha512sum(attachment.getSha512sum());
		System.out.println(attachment.getSize());
		response.setSize(attachment.getSize());
		setStatus(attachment, response);
		return response;
	}

	default AttachmentListResponse toAttachmentList(Page<Attachment> page) {
		return setPage(new AttachmentListResponse(), page, this::toResponse);
	}

}
