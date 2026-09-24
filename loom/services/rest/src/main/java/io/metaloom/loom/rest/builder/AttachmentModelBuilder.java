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
		response.setSize(attachment.getSize());
		setStatus(attachment, response);
		return response;
	}

	default AttachmentListResponse toAttachmentList(Page<Attachment> page) {
		return setPage(new AttachmentListResponse(), page, this::toResponse);
	}

	/**
	 * A complete, unpaged list of attachments.
	 *
	 * <p>
	 * For the collections that are small by construction rather than by query — a chat's files are capped at
	 * {@code LOOM_CHAT_ATTACHMENT_MAX_FILES}, so paging them would be ceremony over a list of ten. The paging metadata is still filled in, because the
	 * response shape is shared with the paged routes and a client should not have to know which kind it asked for.
	 * </p>
	 */
	default AttachmentListResponse toAttachmentList(java.util.List<Attachment> attachments) {
		AttachmentListResponse response = new AttachmentListResponse();
		// Set explicitly so an empty list serializes as [] rather than null: a chat with no files is
		// the ordinary case here, and a client should not have to null-check it.
		response.setData(new java.util.ArrayList<>());
		java.util.UUID lastUuid = null;
		for (Attachment attachment : attachments) {
			response.add(toResponse(attachment));
			lastUuid = attachment.getUuid();
		}
		io.metaloom.loom.rest.model.common.PagingInfo metainfo = new io.metaloom.loom.rest.model.common.PagingInfo();
		metainfo.setPerPage((long) attachments.size());
		metainfo.setTotalCount(attachments.size());
		metainfo.setLastUuid(lastUuid);
		return response.setMetainfo(metainfo);
	}

}
