package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;

public class PersonEndpointTest extends AbstractCRUDEndpointTest {

	private PersonResponse createTestPerson(LoomHttpClient client) throws LoomClientException {
		PersonCreateRequest request = new PersonCreateRequest();
		request.setAlias("test-alias");
		request.setFirstname("Test");
		request.setLastname("Person");
		return client.createPerson(request).sync().body();
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		PersonResponse created = createTestPerson(client);
		PersonResponse person = client.loadPerson(created.getUuid()).sync().body();
		assertThat(person).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		PersonCreateRequest request = new PersonCreateRequest();
		request.setAlias("dummy-alias");
		request.setFirstname("John");
		request.setLastname("Doe");
		PersonResponse person = client.createPerson(request).sync().body();
		assertThat(person).isValid();

		PersonResponse person2 = client.loadPerson(person.getUuid()).sync().body();
		assertThat(person).matches(person2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		PersonResponse created = createTestPerson(client);
		client.deletePerson(created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadPerson(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		PersonResponse created = createTestPerson(client);
		PersonUpdateRequest update = new PersonUpdateRequest();
		update.setAlias("updated-alias");
		update.setFirstname("Updated");
		update.setLastname("Name");
		PersonResponse response = client.updatePerson(created.getUuid(), update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			PersonCreateRequest request = new PersonCreateRequest();
			request.setAlias("person-alias-" + i);
			client.createPerson(request).sync().body();
		}
		PersonListResponse list = client.listPersons().sync().body();
		assertThat(list).isValid().hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		PersonCreateRequest request = new PersonCreateRequest();
		request.setAlias("perm-check");
		return client.createPerson(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadPerson(UUID.randomUUID());
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listPersons();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deletePerson(UUID.randomUUID());
	}

}
