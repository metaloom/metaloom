package io.metaloom.loom.rest.model.person;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class PersonVoiceSampleListResponse extends AbstractListResponse<PersonVoiceSampleListResponse, PersonVoiceSampleResponse> {

	public PersonVoiceSampleListResponse() {
	}

	@Override
	public PersonVoiceSampleListResponse self() {
		return this;
	}
}
