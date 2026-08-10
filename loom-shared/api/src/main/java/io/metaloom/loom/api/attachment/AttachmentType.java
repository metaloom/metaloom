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
	PERSON_IMAGE,

	/**
	 * The picture of a user account, owned by that account.
	 *
	 * <p>
	 * Uploaded to the account through {@code POST /users/:uuid/avatar} or {@code POST /me/avatar}, and shown wherever the UI renders a username. Like
	 * {@link #PERSON_IMAGE} it is derived from nothing, so it carries {@code user_uuid} and leaves the asset, embedding and detection pointers null
	 * (V2.93).
	 * </p>
	 *
	 * <p>
	 * Unlike a person image there is at most one per account: a partial unique index enforces it, so an upload replaces the previous picture instead
	 * of appending to a gallery. A person is a subject face detection keeps finding in new material; an account is not.
	 * </p>
	 */
	USER_AVATAR;

}
