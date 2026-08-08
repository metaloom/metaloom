package io.metaloom.loom.db.model.attachment;

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

}
