package io.metaloom.loom.db.model.attachment;

import java.util.UUID;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.CUDElement;
import io.metaloom.utils.hash.SHA512;

public interface Attachment extends CUDElement<Attachment> {

	String getFilename();

	Attachment setFilename(String filename);

	String getMimeType();

	Attachment setMimeType(String mimeType);

	long getSize();

	Attachment setSize(long size);

	SHA512 getSha512sum();

	Attachment setSha512sum(SHA512 sha512sum);

	UUID getEmbeddingUuid();

	Attachment setEmbeddingUuid(UUID uuid);

	UUID getAssetUuid();

	Attachment setAssetUuid(UUID assetUuid);

	AttachmentType getType();

	Attachment setType(AttachmentType type);

	/**
	 * The storage pool holding this attachment's bytes, from {@code attachment_binary.pool_uuid}.
	 *
	 * <p>
	 * There is no locator column: {@code attachment_binary} is keyed by {@code sha512sum}, so the object key is derived from the hash via
	 * {@code BinaryStorage.locatorFor}. Only the backend has to be recorded. NULL means the local upload directory.
	 * </p>
	 *
	 * @return the pool uuid, or null for the default local storage
	 */
	UUID getPoolUuid();

	Attachment setPoolUuid(UUID poolUuid);

	/**
	 * The detection this binary depicts, for a {@link io.metaloom.loom.api.attachment.AttachmentType#FACE_CROP}.
	 *
	 * <p>
	 * Null for every other attachment type. A face crop belongs to one detected face rather than to the whole asset, which is why the asset pointer
	 * alone could not address it - an asset has many faces.
	 * </p>
	 */
	UUID getDetectionUuid();

	Attachment setDetectionUuid(UUID detectionUuid);

	/**
	 * The person that owns this binary, for a {@link io.metaloom.loom.api.attachment.AttachmentType#PERSON_IMAGE}.
	 *
	 * <p>
	 * Null for every other attachment type. A person image is the one kind of attachment that is not derived from anything: it was uploaded to the
	 * person, or copied into the person's own keeping from a face crop. It therefore leaves {@code assetUuid} and {@code detectionUuid} null, and no
	 * asset deletion can reach it (V2.90).
	 * </p>
	 */
	UUID getPersonUuid();

	Attachment setPersonUuid(UUID personUuid);

	/**
	 * The user account that owns this binary, for a {@link io.metaloom.loom.api.attachment.AttachmentType#USER_AVATAR}.
	 *
	 * <p>
	 * Null for every other attachment type. Like a person image this is not derived from anything - it was uploaded to the account - so it leaves
	 * {@code assetUuid} and {@code detectionUuid} null and no asset deletion can reach it (V2.93). Unlike a person image there is at most one per
	 * account, enforced by a partial unique index rather than by convention, so an upload replaces rather than appends.
	 * </p>
	 *
	 * <p>
	 * Not to be confused with the {@code userUuid} argument of {@link AttachmentDao#createAttachment}, which is the creator of the row.
	 * </p>
	 */
	UUID getUserUuid();

	Attachment setUserUuid(UUID userUuid);

	/**
	 * Discriminator between attachments of the same type for the same target, e.g. the longest edge of a crop.
	 *
	 * <p>
	 * Part of the idempotency key, so re-running a producer rewrites its own variant instead of appending another one.
	 * </p>
	 */
	String getVariant();

	Attachment setVariant(String variant);

	/** The node kind that produced this binary, or null when a user uploaded it. */
	String getNodeKind();

	Attachment setNodeKind(String nodeKind);

}
