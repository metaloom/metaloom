package io.metaloom.loom.rest.model.asset;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Response model for bulk asset creation. Contains individual results for each asset in the request.
 */
public class AssetBulkResponse implements RestResponseModel<AssetBulkResponse> {

	@JsonPropertyDescription("List of individual results for each asset in the bulk request.")
	private List<AssetBulkItemResponse> items = new ArrayList<>();

	@JsonPropertyDescription("Total number of assets in the request.")
	private int total;

	@JsonPropertyDescription("Number of successfully created assets.")
	private int created;

	@JsonPropertyDescription("Number of failed assets.")
	private int failed;

	public AssetBulkResponse() {
	}

	public List<AssetBulkItemResponse> getItems() {
		return items;
	}

	public AssetBulkResponse setItems(List<AssetBulkItemResponse> items) {
		this.items = items;
		return this;
	}

	public AssetBulkResponse add(AssetBulkItemResponse item) {
		this.items.add(item);
		return this;
	}

	public int getTotal() {
		return total;
	}

	public AssetBulkResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	public int getCreated() {
		return created;
	}

	public AssetBulkResponse setCreated(int created) {
		this.created = created;
		return this;
	}

	public int getFailed() {
		return failed;
	}

	public AssetBulkResponse setFailed(int failed) {
		this.failed = failed;
		return this;
	}

	@Override
	public AssetBulkResponse self() {
		return this;
	}

}
