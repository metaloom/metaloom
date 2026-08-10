package io.metaloom.loom.rest.model.person;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class PersonImageListResponse extends AbstractListResponse<PersonImageListResponse, PersonImageResponse> {

	public PersonImageListResponse() {
	}

	@Override
	public PersonImageListResponse self() {
		return this;
	}
}
