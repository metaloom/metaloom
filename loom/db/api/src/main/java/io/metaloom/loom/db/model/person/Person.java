package io.metaloom.loom.db.model.person;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

public interface Person extends CUDElement<Person> {

	String getAlias();

	Person setAlias(String alias);

	String getFirstname();

	Person setFirstname(String firstname);

	String getLastname();

	Person setLastname(String lastname);

	/**
	 * The person image shown as this person's avatar, or null.
	 *
	 * <p>
	 * Points at one of the person's own images - an {@code attachment} of type
	 * {@link io.metaloom.loom.api.attachment.AttachmentType#PERSON_IMAGE} owned by this person - never into an asset. Its predecessor
	 * {@code primary_image_uuid} pointed at an asset, which for a person discovered in a video resolved to the whole video file (V2.91).
	 * </p>
	 */
	UUID getAvatarAttachmentUuid();

	Person setAvatarAttachmentUuid(UUID avatarAttachmentUuid);

}
