package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;

public interface PersonMethods {

	LoomClientRequest<PersonResponse> loadPerson(UUID personUuid);

	LoomClientRequest<PersonResponse> createPerson(PersonCreateRequest request);

	LoomClientRequest<PersonResponse> updatePerson(UUID personUuid, PersonUpdateRequest request);

	LoomClientRequest<PersonListResponse> listPersons();

	LoomClientRequest<NoResponse> deletePerson(UUID personUuid);

}
