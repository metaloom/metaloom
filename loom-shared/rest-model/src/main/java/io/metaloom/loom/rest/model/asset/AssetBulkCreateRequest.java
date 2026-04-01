package io.metaloom.loom.rest.model.asset;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request model for bulk asset creation. Contains a list of individual asset create requests.
 */
public class AssetBulkCreateRequest implements RestRequestModel {

	@JsonPropertyDescription("List of asset creation requests to be processed in bulk.")
	private List<AssetCreateRequest> assets = new ArrayList<>();

	public AssetBulkCreateRequest() {
	}

	public List<AssetCreateRequest> getAssets() {
		return assets;
	}

	public AssetBulkCreateRequest setAssets(List<AssetCreateRequest> assets) {
		this.assets = assets;
		return this;
	}

	public AssetBulkCreateRequest add(AssetCreateRequest request) {
		this.assets.add(request);
		return this;
	}

}
