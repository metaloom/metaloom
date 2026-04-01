package io.metaloom.loom.client.common.method;

import java.io.InputStream;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentListResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentUpdateRequest;

public interface AttachmentMethods {

	LoomClientRequest<AttachmentResponse> loadAttachment(UUID attachmentUuid);

	LoomClientRequest<AttachmentResponse> uploadAttachment(String filename, String mimeType, InputStream stream);

	LoomClientRequest<LoomBinaryResponse> downloadAttachment(UUID attachmentUuid);

	LoomClientRequest<AttachmentResponse> updateAttachment(UUID attachmentUuid, AttachmentUpdateRequest request);

	LoomClientRequest<AttachmentListResponse> listAttachments();

	LoomClientRequest<NoResponse> deleteAttachment(UUID attachmentUuid);
}
