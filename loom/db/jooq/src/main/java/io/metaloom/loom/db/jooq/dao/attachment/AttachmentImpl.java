package io.metaloom.loom.db.jooq.dao.attachment;

import java.util.UUID;

import javax.persistence.Column;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.utils.hash.SHA512;

public class AttachmentImpl extends AbstractEditableElement<Attachment> implements Attachment {

	private String filename;

	@Column(name = "binary.sha512sum")
	private SHA512 sha512sum;

	private String mimeType;

	private UUID assetUuid;

	private UUID embeddingUUID;

	@Column(name = "binary.size")
	private long size;

	private AttachmentType type;

	@Override
	public String getFilename() {
		return filename;
	}

	@Override
	public Attachment setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	@Override
	public SHA512 getSha512sum() {
		return sha512sum;
	}

	public SHA512 binarySha512sum() {
		return sha512sum;
	}

	@Override
	public Attachment setSha512sum(SHA512 sha512sum) {
		this.sha512sum = sha512sum;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public Attachment setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	@Override
	public long getSize() {
		return size;
	}

	@Override
	public Attachment setSize(long size) {
		this.size = size;
		return this;
	}

	@Override
	public UUID getEmbeddingUuid() {
		return embeddingUUID;
	}

	@Override
	public Attachment setEmbeddingUuid(UUID embeddingUuid) {
		this.embeddingUUID = embeddingUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Attachment setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public AttachmentType getType() {
		return type;
	}

	@Override
	public Attachment setType(AttachmentType type) {
		this.type = type;
		return this;
	}

}
