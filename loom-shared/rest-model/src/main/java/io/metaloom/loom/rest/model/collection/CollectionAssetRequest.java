package io.metaloom.loom.rest.model.collection;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request to add a single asset to a collection.
 */
public class CollectionAssetRequest implements RestRequestModel {

	@JsonPropertyDescription("Uuid of the asset to add to the collection.")
	private UUID assetUuid;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public CollectionAssetRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

}
