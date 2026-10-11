package io.metaloom.loom.rest.builder;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.model.person.PersonPrint;
import io.metaloom.loom.rest.model.person.PersonPrintResponse;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleResponse;
import io.vertx.core.json.JsonObject;

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
		response.setHeroVoiceUrl(personVoiceSampleUrl(person.getUuid(), person.getHeroVoiceAttachmentUuid()));
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
	 * One of the person's voice samples, with what each voice model made of it.
	 *
	 * <p>
	 * The provenance of a sample cut from an asset is read from its {@code meta.source}; an upload has none. The prints are passed in rather than
	 * loaded here, so a listing can fetch them for every sample with one query.
	 * </p>
	 */
	default PersonVoiceSampleResponse toPersonVoiceSampleResponse(Person person, Attachment sample, Collection<PersonPrint> prints) {
		PersonVoiceSampleResponse response = new PersonVoiceSampleResponse();
		response.setUuid(sample.getUuid());
		response.setFilename(sample.getFilename());
		response.setMimeType(sample.getMimeType());
		response.setSize(sample.getSize());
		response.setUrl(personVoiceSampleUrl(person.getUuid(), sample.getUuid()));
		response.setHero(sample.getUuid() != null && sample.getUuid().equals(person.getHeroVoiceAttachmentUuid()));
		JsonObject meta = sample.getMeta();
		if (meta != null) {
			response.setDurationMs(meta.getLong(VOICE_META_DURATION_MS));
			JsonObject source = meta.getJsonObject(VOICE_META_SOURCE);
			if (source != null) {
				response.setSourceAssetUuid(source.getString("assetUuid"));
				response.setSourceClusterUuid(source.getString("clusterUuid"));
				response.setSourceTimeFrom(source.getLong("timeFrom"));
				response.setSourceTimeTo(source.getLong("timeTo"));
			}
		}
		List<PersonPrintResponse> printResponses = prints == null ? List.of() : prints.stream().map(this::toResponse).toList();
		response.setPrints(new java.util.ArrayList<>(printResponses));
		setStatus(sample, response);
		return response;
	}

	/** What a model made of one sample. Never the vector: nothing outside matching needs a biometric vector, so no response carries one. */
	default PersonPrintResponse toResponse(PersonPrint print) {
		return new PersonPrintResponse()
			.setUuid(print.uuid() == null ? null : print.uuid().toString())
			.setSampleUuid(print.attachmentUuid() == null ? null : print.attachmentUuid().toString())
			.setType(print.type())
			.setModel(print.model())
			.setDimensions(print.dimensions())
			.setUsable(print.usable())
			.setError(print.error())
			.setEdited(print.edited() == null ? null : print.edited().toString());
	}

	/** {@code meta} key of a voice sample's length in milliseconds. */
	String VOICE_META_DURATION_MS = "durationMs";

	/** {@code meta} key of a voice sample's provenance: {@code assetUuid}, {@code clusterUuid}, {@code timeFrom}, {@code timeTo}. */
	String VOICE_META_SOURCE = "source";

	/**
	 * Where a person's voice sample is served from, or null when there is none.
	 */
	static String personVoiceSampleUrl(UUID personUuid, UUID sampleUuid) {
		if (personUuid == null || sampleUuid == null) {
			return null;
		}
		return RESTConstants.API_V1_PATH + "/persons/" + personUuid + "/voice-samples/" + sampleUuid + "/data";
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
