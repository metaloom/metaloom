package io.metaloom.loom.rest.builder;

import java.util.UUID;

import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.RESTConstants;
import io.metaloom.loom.rest.model.person.PersonImageResponse;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;

public interface PersonModelBuilder extends ModelBuilder, UserModelBuilder {

	default PersonResponse toResponse(Person person) {
		PersonResponse response = new PersonResponse();
		response.setUuid(person.getUuid());
		response.setAlias(person.getAlias());
		response.setFirstname(person.getFirstname());
		response.setLastname(person.getLastname());
		// A URL rather than a uuid: how a person's picture is addressed is this layer's business, not the caller's. Both uuids the URL needs are on the
		// person row, so this stays a pure function of what was already loaded.
		response.setAvatarUrl(personImageUrl(person.getUuid(), person.getAvatarAttachmentUuid()));
		setStatus(person, response);
		return response;
	}

	default PersonListResponse toPersonList(Page<Person> page) {
		return setPage(new PersonListResponse(), page, this::toResponse);
	}

	default PersonImageResponse toPersonImageResponse(Person person, Attachment image) {
		PersonImageResponse response = new PersonImageResponse();
		response.setUuid(image.getUuid());
		response.setFilename(image.getFilename());
		response.setMimeType(image.getMimeType());
		response.setSize(image.getSize());
		response.setUrl(personImageUrl(person.getUuid(), image.getUuid()));
		response.setAvatar(image.getUuid() != null && image.getUuid().equals(person.getAvatarAttachmentUuid()));
		setStatus(image, response);
		return response;
	}

	/**
	 * Where a person's image is served from, or null when there is no image.
	 */
	private static String personImageUrl(UUID personUuid, UUID imageUuid) {
		if (personUuid == null || imageUuid == null) {
			return null;
		}
		return RESTConstants.API_V1_PATH + "/persons/" + personUuid + "/images/" + imageUuid + "/data";
	}

}
