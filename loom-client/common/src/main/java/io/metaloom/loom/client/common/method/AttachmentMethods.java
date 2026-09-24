package io.metaloom.loom.client.common.method;

import java.io.File;
import java.io.InputStream;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
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

	// ---- chat attachments ------------------------------------------------------------------
	//
	// Files a user dropped into a conversation. A separate surface from the routes above because a
	// chat file is owned by its chat rather than by an asset: it is reachable only through the chat,
	// and the generic routes above deliberately hide it from everyone but the chat's owner.

	/**
	 * Attach a file to a chat.
	 *
	 * @param chatUuid the conversation the file belongs to
	 */
	LoomClientRequest<AttachmentResponse> uploadChatAttachment(UUID chatUuid, File file, String mimeType);

	/** The files attached to a chat, newest first. */
	LoomClientRequest<AttachmentListResponse> listChatAttachments(UUID chatUuid);

	/** Detach a file. The bytes stay in content-addressed storage; only the chat's reference goes. */
	LoomClientRequest<NoResponse> deleteChatAttachment(UUID chatUuid, UUID attachmentUuid);

	/** The raw bytes of a chat attachment. */
	LoomClientRequest<LoomBinaryResponse> downloadChatAttachment(UUID chatUuid, UUID attachmentUuid);

	/**
	 * Save a chat attachment into the media library as a real asset.
	 *
	 * <p>
	 * The attachment stays on the chat: a file can be in the conversation and in the library at once,
	 * and deleting the chat afterwards must not take the asset with it.
	 * </p>
	 *
	 * @param libraryUuid where to file it, or null to use LOOM_CHAT_ATTACHMENT_LIBRARY
	 */
	LoomClientRequest<AssetResponse> saveChatAttachmentToLibrary(UUID chatUuid, UUID attachmentUuid, UUID libraryUuid);

}
