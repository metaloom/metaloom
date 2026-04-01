package io.metaloom.loom.rest.model.tag;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class TagListResponse extends AbstractListResponse<TagListResponse, TagResponse> {

	@Override
	public TagListResponse self() {
		return this;
	}

}
