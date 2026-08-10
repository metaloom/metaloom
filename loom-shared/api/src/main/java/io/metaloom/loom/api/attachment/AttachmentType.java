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
	FACE_CROP,

	/**
	 * A picture of a person, owned by that person.
	 *
	 * <p>
	 * The only attachment type that is not derived from an asset: it is uploaded to the person, or copied from a face crop into the person's own
	 * keeping. It therefore carries {@code person_uuid} and leaves the asset, embedding and detection pointers null, so no asset deletion can reach it
	 * (V2.90). One of a person's images is designated the avatar via {@code person.avatar_attachment_uuid}.
	 * </p>
	 */
	PERSON_IMAGE;

}
