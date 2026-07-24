package io.metaloom.loom.rest.model.jsoncomp;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class JsonCompListResponse extends AbstractListResponse<JsonCompListResponse, JsonCompResponse> {

	@Override
	public JsonCompListResponse self() {
		return this;
	}

}
