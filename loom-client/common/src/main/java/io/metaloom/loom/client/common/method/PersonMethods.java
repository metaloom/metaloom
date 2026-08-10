package io.metaloom.loom.client.common.method;

import java.io.File;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonImageListResponse;
import io.metaloom.loom.rest.model.person.PersonImageResponse;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;

public interface PersonMethods {

	LoomClientRequest<PersonResponse> loadPerson(UUID personUuid);

	LoomClientRequest<PersonResponse> createPerson(PersonCreateRequest request);

	LoomClientRequest<PersonResponse> updatePerson(UUID personUuid, PersonUpdateRequest request);

	LoomClientRequest<PersonListResponse> listPersons();

	LoomClientRequest<NoResponse> deletePerson(UUID personUuid);

	/** The face clusters confirmed to be this person, across every asset they appear in. */
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

}
