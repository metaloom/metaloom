package io.metaloom.loom.rest.model.attachment;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.utils.hash.SHA512;

public class AttachmentResponse extends AbstractCreatorEditorRestResponse<AttachmentResponse>
	implements AttachmentModel<AttachmentResponse> {

	private SHA512 sha512sum;

	private String filename;

	private String mimeType;

	private long size;

	public SHA512 getSha512sum() {
		return sha512sum;
	}

	public AttachmentResponse setSha512sum(SHA512 sha512sum) {
		this.sha512sum = sha512sum;
		return this;
	}

	@Override
	public String getFilename() {
		return filename;
	}

	@Override
	public AttachmentResponse setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public AttachmentResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public long getSize() {
		return size;
	}

	public AttachmentResponse setSize(long size) {
		this.size = size;
		return this;
	}

	@Override
	public AttachmentResponse self() {
		return this;
	}
}
