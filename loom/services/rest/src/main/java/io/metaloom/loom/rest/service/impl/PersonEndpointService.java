package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PERSON;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class PersonEndpointService extends AbstractCRUDEndpointService<PersonDao, Person> {

	@Inject
	public PersonEndpointService(PersonDao personDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(personDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_PERSON, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PERSON, modelBuilder::toPersonList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PERSON, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_PERSON, () -> {
			PersonCreateRequest request = lrc.requestBody(PersonCreateRequest.class);
			validator.validate(request);

			String alias = request.getAlias();
			UUID userUuid = lrc.userUuid();
			Person person = dao().createPerson(userUuid, alias);
			update(request::getFirstname, person::setFirstname);
			update(request::getLastname, person::setLastname);
			update(request::getMeta, person::setMeta);
			return person;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_PERSON, () -> {
			PersonUpdateRequest request = lrc.requestBody(PersonUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Person person = dao().load(id);
			update(request::getAlias, person::setAlias);
			update(request::getFirstname, person::setFirstname);
			update(request::getLastname, person::setLastname);
			update(request::getMeta, person::setMeta);
			setEditor(person, userUuid);
			return person;
		}, modelBuilder::toResponse);
	}

}
