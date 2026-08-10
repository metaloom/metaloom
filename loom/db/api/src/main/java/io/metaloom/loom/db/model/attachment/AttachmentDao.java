package io.metaloom.loom.db.model.attachment;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.utils.hash.SHA512;

public interface AttachmentDao extends CRUDDao<Attachment> {

	Attachment createAttachment(UUID userUuid, SHA512 sha512sum, String filename, long size, String mimeType, AttachmentType type);

	/**
	 * The face crop stored for a detection, or {@code null}.
	 *
	 * <p>
	 * Keyed by {@code (detection_uuid, type, node_kind, variant)} - the same partial unique index the producer upserts on - so a detection can carry
	 * crops at several sizes without them colliding.
	 * </p>
	 *
	 * @param variant the size discriminator, or {@code null} for any
	 */
	Attachment findFaceCrop(UUID detectionUuid, String variant);

	/**
	 * Every image owned by a person, newest first.
	 *
	 * <p>
	 * Unlike the asset and detection targets this has no idempotency key behind it: a person's gallery is a list of pictures a human chose to add, so
	 * two of them may legitimately be byte-identical and neither replaces the other.
	 * </p>
	 */
	List<Attachment> listByPerson(UUID personUuid);

	/**
	 * The avatar picture of a user account, or {@code null}.
	 *
	 * <p>
	 * At most one can exist: {@code idx_attachment_user_avatar_unique} (V2.93) is a partial unique index over {@code user_uuid} for this type, so the
	 * cardinality is a schema fact rather than a convention this method has to defend. That is what lets the upload path replace rather than ask which
	 * of several pictures is meant.
	 * </p>
	 *
	 * @param userUuid the account that owns the picture
	 * @return the attachment, or null when the account has none
	 */
	Attachment loadAvatarByUser(UUID userUuid);

}
