package io.metaloom.loom.rest.model.attachment;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface AttachmentModel<T extends AttachmentModel<T>> extends MetaModel<T>, RestModel {

	String getFilename();

	T setFilename(String filename);

	String getMimeType();

	T setMimeType(String mimeType);

}
