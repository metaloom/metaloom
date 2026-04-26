package io.metaloom.loom.rest.model.person;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class PersonListResponse extends AbstractListResponse<PersonListResponse, PersonResponse> {

	public PersonListResponse() {
	}

	@Override
	public PersonListResponse self() {
		return this;
	}
}
