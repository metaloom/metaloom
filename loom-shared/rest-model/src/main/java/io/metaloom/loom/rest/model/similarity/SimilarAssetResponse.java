package io.metaloom.loom.rest.model.similarity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One near-duplicate hit for an asset, produced by the fingerprint similarity index.
 */
public class SimilarAssetResponse implements RestResponseModel<SimilarAssetResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Uuid of the similar asset.")
	private String assetUuid;

	@JsonPropertyDescription("SHA512 checksum of the similar asset.")
	private String sha512;

	@JsonProperty(required = true)
	@JsonPropertyDescription("k-NN similarity score (higher means more similar). Not a probability.")
	private float score;

	public String getAssetUuid() {
		return assetUuid;
	}

	public SimilarAssetResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getSha512() {
		return sha512;
	}

	public SimilarAssetResponse setSha512(String sha512) {
		this.sha512 = sha512;
		return this;
	}

	public float getScore() {
		return score;
	}

	public SimilarAssetResponse setScore(float score) {
		this.score = score;
		return this;
	}

	@Override
	public SimilarAssetResponse self() {
		return this;
	}
}
