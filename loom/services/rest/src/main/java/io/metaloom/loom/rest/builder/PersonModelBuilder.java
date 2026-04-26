package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;

public interface PersonModelBuilder extends ModelBuilder, UserModelBuilder {

	default PersonResponse toResponse(Person person) {
		PersonResponse response = new PersonResponse();
		response.setUuid(person.getUuid());
		response.setAlias(person.getAlias());
		response.setFirstname(person.getFirstname());
		response.setLastname(person.getLastname());
		if (person.getPrimaryImageUuid() != null) {
			response.setPrimaryImageUuid(person.getPrimaryImageUuid().toString());
		}
		setStatus(person, response);
		return response;
	}

	default PersonListResponse toPersonList(Page<Person> page) {
		return setPage(new PersonListResponse(), page, this::toResponse);
	}

}
