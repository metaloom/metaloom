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

}
