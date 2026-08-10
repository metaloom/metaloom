package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.storage.StorageBackendListResponse;
import io.metaloom.loom.rest.model.storage.StorageReportResponse;

/**
 * The storage report: what is stored, per kind of content, and how much room is left.
 */
public interface StorageMethods {

	/**
	 * What is stored and how full every backend is.
	 *
	 * <p>
	 * Several aggregate scans over the attachment and asset tables. Not something to poll.
	 * </p>
	 *
	 * @return the report request
	 */
	LoomClientRequest<StorageReportResponse> loadStorageReport();

	/**
	 * Just the backends and their capacity, without the catalogue aggregates.
	 *
	 * @return the backend list request
	 */
	LoomClientRequest<StorageBackendListResponse> loadStorageBackends();
}
