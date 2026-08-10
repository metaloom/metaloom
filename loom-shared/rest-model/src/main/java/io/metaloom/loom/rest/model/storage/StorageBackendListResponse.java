package io.metaloom.loom.rest.model.storage;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Just the backends and their capacity, without the catalogue aggregates.
 *
 * <p>
 * Separate from the full report because it is cheap: a {@code statvfs} per backend, no table scans. That is what makes it the one a dashboard can poll
 * and the report is not.
 * </p>
 */
public class StorageBackendListResponse implements RestResponseModel<StorageBackendListResponse> {

	@JsonPropertyDescription("Every storage backend, starting with the default local storage.")
	private List<StorageBackendModel> backends = new ArrayList<>();

	@JsonPropertyDescription("The configured upload and free-space limits the watermarks are graded against.")
	private StorageThresholdsModel thresholds;

	public StorageBackendListResponse() {
	}

	public List<StorageBackendModel> getBackends() {
		return backends;
	}

	public StorageBackendListResponse setBackends(List<StorageBackendModel> backends) {
		this.backends = backends;
		return this;
	}

	public StorageBackendListResponse add(StorageBackendModel backend) {
		this.backends.add(backend);
		return this;
	}

	public StorageThresholdsModel getThresholds() {
		return thresholds;
	}

	public StorageBackendListResponse setThresholds(StorageThresholdsModel thresholds) {
		this.thresholds = thresholds;
		return this;
	}

	@Override
	public StorageBackendListResponse self() {
		return this;
	}
}
