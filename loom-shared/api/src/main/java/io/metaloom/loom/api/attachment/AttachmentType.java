package io.metaloom.loom.api.attachment;

public enum AttachmentType {

	ASSET_THUMBNAIL,

	EMBEDDING_ATTACHMENT,

	/**
	 * A cropped face, keyed to the detection it depicts.
	 *
	 * <p>
	 * Written by the face-detection node from the crop it already cuts to compute the embedding, so the reviewer sees exactly the image the detector
	 * aligned on. It exists so face crops can be served from the deployment's own storage: embeddings are biometric identifiers, and the review UI
	 * previously fetched stand-in portraits from a third-party avatar service.
	 * </p>
	 */
	FACE_CROP;

}
