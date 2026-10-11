package io.metaloom.loom.client.common.method;

import java.io.File;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonHeroVoiceRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonImageListResponse;
import io.metaloom.loom.rest.model.person.PersonImageResponse;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonMatchResponse;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.metaloom.loom.rest.model.person.PersonPrintResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleImportRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleListResponse;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleResponse;

public interface PersonMethods {

	LoomClientRequest<PersonResponse> loadPerson(UUID personUuid);

	LoomClientRequest<PersonResponse> createPerson(PersonCreateRequest request);

	LoomClientRequest<PersonResponse> updatePerson(UUID personUuid, PersonUpdateRequest request);

	LoomClientRequest<PersonListResponse> listPersons();

	LoomClientRequest<NoResponse> deletePerson(UUID personUuid);

	/** The face and speaker clusters confirmed to be this person, across every asset they appear in. */
	LoomClientRequest<ClusterListResponse> listPersonClusters(UUID personUuid);

	/** The person's own pictures, newest first. */
	LoomClientRequest<PersonImageListResponse> listPersonImages(UUID personUuid);

	/**
	 * Upload a picture of this person.
	 *
	 * @param poolUuid
	 *            storage pool for the bytes, or null for the deployment's default storage
	 */
	LoomClientRequest<PersonImageResponse> uploadPersonImage(UUID personUuid, File file, String mimeType, UUID poolUuid);

	/** Copy a detection's face crop into the person's own images, where it outlives the asset the face was found in. */
	LoomClientRequest<PersonImageResponse> importPersonImage(UUID personUuid, PersonImageImportRequest request);

	LoomClientRequest<LoomBinaryResponse> downloadPersonImage(UUID personUuid, UUID imageUuid);

	LoomClientRequest<NoResponse> deletePersonImage(UUID personUuid, UUID imageUuid);

	/** Designate one of the person's images as their avatar, or clear it with a blank {@code imageUuid}. */
	LoomClientRequest<PersonResponse> setPersonAvatar(UUID personUuid, PersonAvatarRequest request);

	/** Store what a face model made of one of the person's images - a reference vector, or why there is none. */
	LoomClientRequest<PersonPrintResponse> storePersonImagePrint(UUID personUuid, UUID imageUuid, PersonPrintCreateRequest request);

	/** The person's own voice samples, newest first, each with the voice models that have embedded it. */
	LoomClientRequest<PersonVoiceSampleListResponse> listPersonVoiceSamples(UUID personUuid);

	/**
	 * Upload a recording of this person's voice.
	 *
	 * @param poolUuid
	 *            storage pool for the bytes, or null for the deployment's default storage
	 */
	LoomClientRequest<PersonVoiceSampleResponse> uploadPersonVoiceSample(UUID personUuid, File file, String mimeType, UUID poolUuid);

	/** Cut a speaker's voice out of the asset it was heard in into the person's own samples. */
	LoomClientRequest<PersonVoiceSampleResponse> importPersonVoiceSample(UUID personUuid, PersonVoiceSampleImportRequest request);

	LoomClientRequest<LoomBinaryResponse> downloadPersonVoiceSample(UUID personUuid, UUID sampleUuid);

	LoomClientRequest<NoResponse> deletePersonVoiceSample(UUID personUuid, UUID sampleUuid);

	/** Store what a voice model made of one of the person's samples - a reference vector, or why there is none. */
	LoomClientRequest<PersonPrintResponse> storePersonVoicePrint(UUID personUuid, UUID sampleUuid, PersonPrintCreateRequest request);

	/** Designate one of the person's voice samples as their hero sample, or clear it with a blank {@code sampleUuid}. */
	LoomClientRequest<PersonResponse> setPersonHeroVoice(UUID personUuid, PersonHeroVoiceRequest request);

	/** Which known persons a set of face or voice vectors resembles. Suggests; attributes nothing. */
	LoomClientRequest<PersonMatchResponse> matchPersons(PersonMatchRequest request);

}
