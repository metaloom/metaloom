package io.metaloom.loom.rest.model.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Takes a copy of a detection's face crop into a person's own images.
 *
 * <p>
 * This is what gives a person discovered in a video a picture of their face rather than of the video file. It is a copy and not a reference: the new
 * image carries the same content-addressed bytes but belongs to the person, so deleting the asset the face was found in leaves it standing.
 * </p>
 *
 * <p>
 * Importing is a deliberate act by whoever is looking at the person. Confirming a cluster does not do it - a review verdict records who attributed a
 * face to whom, and choosing what someone looks like is a different decision.
 * </p>
 */
public class PersonImageImportRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("UUID of the detection whose face crop should become one of the person's images.")
	private String detectionUuid;

	public PersonImageImportRequest() {
	}

	public String getDetectionUuid() {
		return detectionUuid;
	}

	public PersonImageImportRequest setDetectionUuid(String detectionUuid) {
		this.detectionUuid = detectionUuid;
		return this;
	}
}
