package io.metaloom.loom.rest.model.collection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Outcome of a bulk membership write.
 *
 * <p>
 * {@code added} counts the assets that were not members before the call. Because membership is idempotent, an asset that was already in the
 * collection is neither an error nor an addition - it is simply absent from both counters, which is what lets a caller distinguish "nothing to do"
 * from "something went wrong".
 * </p>
 */
public class CollectionAssetBulkResponse implements RestResponseModel<CollectionAssetBulkResponse> {

	@JsonPropertyDescription("Number of asset uuids in the request.")
	private int total;

	@JsonPropertyDescription("Number of assets that became new members of the collection.")
	private int added;

	@JsonPropertyDescription("Number of asset uuids that could not be linked, for example because no such asset exists.")
	private int failed;

	@JsonPropertyDescription("The asset uuids that could not be linked.")
	private List<UUID> failedUuids = new ArrayList<>();

	public int getTotal() {
		return total;
	}

	public CollectionAssetBulkResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	public int getAdded() {
		return added;
	}

	public CollectionAssetBulkResponse setAdded(int added) {
		this.added = added;
		return this;
	}

	public int getFailed() {
		return failed;
	}

	public CollectionAssetBulkResponse setFailed(int failed) {
		this.failed = failed;
		return this;
	}

	public List<UUID> getFailedUuids() {
		return failedUuids;
	}

	public CollectionAssetBulkResponse setFailedUuids(List<UUID> failedUuids) {
		this.failedUuids = failedUuids;
		return this;
	}

	public CollectionAssetBulkResponse addFailed(UUID assetUuid) {
		this.failedUuids.add(assetUuid);
		this.failed++;
		return this;
	}

	@Override
	public CollectionAssetBulkResponse self() {
		return this;
	}

}
