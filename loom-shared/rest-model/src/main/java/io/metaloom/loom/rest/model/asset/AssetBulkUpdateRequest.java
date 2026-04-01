package io.metaloom.loom.rest.model.asset;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request model for bulk asset updates. Contains a list of individual asset update entries keyed by SHA-512.
 */
public class AssetBulkUpdateRequest implements RestRequestModel {

	@JsonPropertyDescription("List of asset update entries to be processed in bulk.")
	private List<AssetBulkUpdateEntry> assets = new ArrayList<>();

	public AssetBulkUpdateRequest() {
	}

	public List<AssetBulkUpdateEntry> getAssets() {
		return assets;
	}

	public AssetBulkUpdateRequest setAssets(List<AssetBulkUpdateEntry> assets) {
		this.assets = assets;
		return this;
	}

	public AssetBulkUpdateRequest add(AssetBulkUpdateEntry entry) {
		this.assets.add(entry);
		return this;
	}

}
