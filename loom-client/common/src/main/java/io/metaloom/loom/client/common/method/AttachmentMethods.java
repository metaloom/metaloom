package io.metaloom.loom.client.common.method;

import java.io.File;
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

	/**
	 * Upload an attachment from a local file, optionally binding it to an asset.
	 *
	 * @param file
	 *            the file to upload
	 * @param mimeType
	 *            content type, or null for {@code application/octet-stream}
	 * @param assetUuid
	 *            asset this attachment describes, or null. When set, the bytes land in the same storage pool as that asset's binary
	 * @param type
	 *            attachment type name (e.g. {@code CONTACT_SHEET}, {@code POSTER_FRAME}, {@code WAVEFORM}, {@code PROXY},
	 *            {@code EXTRACTED_AUDIO}), or null for the default
	 * @return the request
	 */
	LoomClientRequest<AttachmentResponse> uploadAttachment(File file, String mimeType, UUID assetUuid, String type);

	/**
	 * Upload a cropped face, bound to the detection it depicts.
	 *
	 * <p>
	 * A face crop belongs to one detected face rather than to a whole asset - an asset has many faces - so the detection uuid is what addresses it
	 * later. Uploading the same {@code (detection, variant)} again replaces the previous crop rather than adding a second one.
	 * </p>
	 *
	 * @param file          the encoded crop
	 * @param assetUuid     the asset the face was found in; decides which storage pool the bytes land in
	 * @param detectionUuid the detection the crop depicts
	 * @param variant       size discriminator, e.g. the longest edge in pixels
	 * @param nodeKind      the producing node kind
	 */
	LoomClientRequest<AttachmentResponse> uploadFaceCrop(File file, UUID assetUuid, UUID detectionUuid, String variant, String nodeKind);

	/**
	 * Download an attachment's raw bytes from {@code GET /attachments/:uuid/data}.
	 *
	 * @param attachmentUuid
	 *            the attachment
	 * @return the request, yielding a streaming response the caller must close
	 */
	LoomClientRequest<LoomBinaryResponse> downloadAttachment(UUID attachmentUuid);

	LoomClientRequest<AttachmentResponse> updateAttachment(UUID attachmentUuid, AttachmentUpdateRequest request);

	LoomClientRequest<AttachmentListResponse> listAttachments();

	LoomClientRequest<NoResponse> deleteAttachment(UUID attachmentUuid);
}
