package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PERSON;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
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
			applyPrimaryImage(request.getPrimaryImageUuid(), person);
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
			applyPrimaryImage(request.getPrimaryImageUuid(), person);
			setEditor(person, userUuid);
			return person;
		}, modelBuilder::toResponse);
	}

	/**
	 * {@code GET /api/v1/persons/:uuid/clusters} - every cluster confirmed to be this person.
	 *
	 * <p>
	 * The inverse of the confirmation: given a person, which groups of faces were attributed to them, across which assets.
	 * </p>
	 */
	public void listClusters(LoomRoutingContext lrc, UUID personUuid) {
		checkPerm(lrc, READ_PERSON, () -> {
			if (dao().load(personUuid) == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Person not found.");
			}
			List<Cluster> clusters = daos().clusterDao().findByPerson(personUuid);
			Map<UUID, Long> counts = daos().clusterDao().countMembers(clusters.stream().map(Cluster::getUuid).toList());

			ClusterListResponse response = new ClusterListResponse();
			clusters.forEach(cluster -> response.add(modelBuilder.toResponse(cluster, counts.getOrDefault(cluster.getUuid(), 0L))));
			lrc.send(response);
		});
	}

	/**
	 * Apply the person's avatar pointer.
	 *
	 * <p>
	 * This used to be silently skipped in both create and update: the DTO carries the uuid as a {@code String} while the entity holds a {@code UUID},
	 * and the generic {@code update(getter, setter)} helper cannot bridge the two. The validator passed it, the response builder read it back, and
	 * nothing in between ever wrote it - so a person's avatar could never be set at all.
	 * </p>
	 */
	private void applyPrimaryImage(String raw, Person person) {
		if (raw == null) {
			// Absent means "leave it alone", consistent with every other field on a partial update.
			return;
		}
		if (raw.isBlank()) {
			// Explicitly blank means "clear it" - the only way to remove an avatar.
			person.setPrimaryImageUuid(null);
			return;
		}
		try {
			person.setPrimaryImageUuid(UUID.fromString(raw));
		} catch (IllegalArgumentException e) {
			// A malformed uuid is a bad request, not an internal error - without this it surfaced as a 500 from deep inside the DAO.
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The 'primaryImageUuid' is not a valid uuid.");
		}
	}

}
