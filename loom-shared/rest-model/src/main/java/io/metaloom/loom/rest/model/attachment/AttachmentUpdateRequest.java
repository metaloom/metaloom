package io.metaloom.loom.rest.model.attachment;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class AttachmentUpdateRequest extends AbstractMetaModel<AttachmentUpdateRequest>
	implements AttachmentModel<AttachmentUpdateRequest>, RestRequestModel {

	private String filename;

	private String mimeType;

	@Override
	public String getFilename() {
		return filename;
	}

	@Override
	public AttachmentUpdateRequest setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	@Override
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public AttachmentUpdateRequest setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	@Override
	public AttachmentUpdateRequest self() {
		return this;
	}

}
