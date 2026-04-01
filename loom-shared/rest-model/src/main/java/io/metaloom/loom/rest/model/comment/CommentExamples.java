package io.metaloom.loom.rest.model.comment;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface CommentExamples extends ExampleValues {

	default Example commentUpdateRequestExample() {
		return new ExampleImpl(commentUpdateRequest(), "The comment update request", HttpResponseStatus.OK);
	}

	default Example commentCreateRequestExample() {
		return new ExampleImpl(commentCreateRequest(), "The comment create request", HttpResponseStatus.CREATED);
	}

	default Example commentResponseExample() {
		return new ExampleImpl(commentResponse(), "The comment response", HttpResponseStatus.OK);
	}

	default Example commentListResponseExample() {
		return new ExampleImpl(commentListResponse(), "The comment list response", HttpResponseStatus.OK);
	}

	default CommentResponse commentResponse() {
		CommentResponse model = new CommentResponse();
		model.setUuid(uuidC());
		model.setTitle("The Title");
		model.setText("The comment text");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default CommentListResponse commentListResponse() {
		CommentListResponse model = new CommentListResponse();
		model.setMetainfo(pagingInfo());
		model.add(commentResponse());
		model.add(commentResponse());
		return model;
	}

	default CommentUpdateRequest commentUpdateRequest() {
		CommentUpdateRequest model = new CommentUpdateRequest();
		model.setTitle("The comment title");
		model.setText("The comment text");
		model.setMeta(meta());
		return model;
	}

	default CommentCreateRequest commentCreateRequest() {
		CommentCreateRequest model = new CommentCreateRequest();
		model.setTitle("The comment title");
		model.setText("The comment text");
		model.setMeta(meta());
		return model;
	}

}
