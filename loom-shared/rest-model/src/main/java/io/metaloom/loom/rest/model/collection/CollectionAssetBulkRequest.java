package io.metaloom.loom.rest.model.collection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request to add several assets to a collection in one call.
 */
public class CollectionAssetBulkRequest implements RestRequestModel {

	@JsonPropertyDescription("Uuids of the assets to add to the collection.")
	private List<UUID> assetUuids = new ArrayList<>();

	public List<UUID> getAssetUuids() {
		return assetUuids;
	}

	public CollectionAssetBulkRequest setAssetUuids(List<UUID> assetUuids) {
		this.assetUuids = assetUuids;
		return this;
	}

	public CollectionAssetBulkRequest add(UUID assetUuid) {
		this.assetUuids.add(assetUuid);
		return this;
	}

}
