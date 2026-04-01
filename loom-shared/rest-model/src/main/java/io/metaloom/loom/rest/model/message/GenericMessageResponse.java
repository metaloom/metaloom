package io.metaloom.loom.rest.model.message;

import io.metaloom.loom.rest.model.RestResponseModel;

public class GenericMessageResponse implements RestResponseModel<GenericMessageResponse> {

	private String message;

	public String getMessage() {
		return message;
	}

	public GenericMessageResponse setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public GenericMessageResponse self() {
		return this;
	}
}
