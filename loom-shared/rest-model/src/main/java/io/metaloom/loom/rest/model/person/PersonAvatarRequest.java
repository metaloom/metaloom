package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Designates which of a person's images is their avatar.
 *
 * <p>
 * A null or blank {@code imageUuid} clears the avatar. That is deliberate rather than a separate DELETE route: "no avatar" is a value the field can
 * hold, not the absence of the resource.
 * </p>
 */
public class PersonAvatarRequest implements RestRequestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("UUID of one of the person's images, or null/blank to clear the avatar.")
	private String imageUuid;

	public PersonAvatarRequest() {
	}

	public String getImageUuid() {
		return imageUuid;
	}

	public PersonAvatarRequest setImageUuid(String imageUuid) {
		this.imageUuid = imageUuid;
		return this;
	}
}
