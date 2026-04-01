package io.metaloom.loom.rest.model.attachment;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AttachmentExamples extends ExampleValues {

//	default Example attachmentCreateRequestExample() {
//		return new ExampleImpl(attachmentCreateRequest(), "The attachment create request", HttpResponseStatus.CREATED);
//	}
	
	default Example attachmentUpdateRequestExample() {
		return new ExampleImpl(attachmentUpdateRequest(), "The attachment update request", HttpResponseStatus.OK);
	}

	default Example attachmentResponseExample() {
		return new ExampleImpl(attachmentResponse(), "The attachment response", HttpResponseStatus.OK);
	}

	default Example attachmentListResponseExample() {
		return new ExampleImpl(attachmentListResponse(), "The attachment list response", HttpResponseStatus.OK);
	}

	default AttachmentResponse attachmentResponse() {
		AttachmentResponse model = new AttachmentResponse();
		model.setUuid(uuidC());
		model.setFilename("flower.jpg");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default AttachmentListResponse attachmentListResponse() {
		AttachmentListResponse model = new AttachmentListResponse();
		model.setMetainfo(pagingInfo());
		model.add(attachmentResponse());
		model.add(attachmentResponse());
		return model;
	}

	default AttachmentUpdateRequest attachmentUpdateRequest() {
		AttachmentUpdateRequest model = new AttachmentUpdateRequest();
		model.setFilename("updated_flower.jpg");
		model.setMeta(meta());
		return model;
	}

}
