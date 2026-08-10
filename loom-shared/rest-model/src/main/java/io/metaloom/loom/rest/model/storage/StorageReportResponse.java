package io.metaloom.loom.rest.model.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What this deployment has stored, and how much room is left.
 *
 * <p>
 * Computed on every request from the catalogue and from the storage backends themselves - nothing here is a cached column that somebody has to
 * remember to update. It is several aggregate scans, so it is a screen an operator opens, not something to poll.
 * </p>
 */
public class StorageReportResponse implements RestResponseModel<StorageReportResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("When this report was computed.")
	private Instant timestamp;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The configured upload and free-space limits the watermarks are graded against.")
	private StorageThresholdsModel thresholds;

	@JsonProperty(required = true)
	@JsonPropertyDescription("One entry per kind of stored content, always all of them - a kind with nothing in it reports zeros rather than being omitted.")
	private List<StorageCategoryModel> categories = new ArrayList<>();

	@JsonProperty(required = true)
	@JsonPropertyDescription("Every storage backend this deployment writes to, starting with the default local storage.")
	private List<StorageBackendModel> backends = new ArrayList<>();

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many distinct stored attachment objects exist in total.")
	private long objects;

	@JsonProperty(required = true)
	@JsonPropertyDescription("What those objects occupy in bytes. This is the physical total and is NOT the sum of the categories' distinctBytes, because one object can belong to two categories.")
	private long distinctBytes;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Stored attachment objects that no element references any more. Deleting an attachment removes the record but not yet the bytes.")
	private long orphanObjects;

	@JsonProperty(required = true)
	@JsonPropertyDescription("What those unreferenced objects occupy in bytes.")
	private long orphanBytes;

	public StorageReportResponse() {
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public StorageReportResponse setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
		return this;
	}

	public StorageThresholdsModel getThresholds() {
		return thresholds;
	}

	public StorageReportResponse setThresholds(StorageThresholdsModel thresholds) {
		this.thresholds = thresholds;
		return this;
	}

	public List<StorageCategoryModel> getCategories() {
		return categories;
	}

	public StorageReportResponse setCategories(List<StorageCategoryModel> categories) {
		this.categories = categories;
		return this;
	}

	public StorageReportResponse add(StorageCategoryModel category) {
		this.categories.add(category);
		return this;
	}

	public List<StorageBackendModel> getBackends() {
		return backends;
	}

	public StorageReportResponse setBackends(List<StorageBackendModel> backends) {
		this.backends = backends;
		return this;
	}

	public StorageReportResponse add(StorageBackendModel backend) {
		this.backends.add(backend);
		return this;
	}

	public long getObjects() {
		return objects;
	}

	public StorageReportResponse setObjects(long objects) {
		this.objects = objects;
		return this;
	}

	public long getDistinctBytes() {
		return distinctBytes;
	}

	public StorageReportResponse setDistinctBytes(long distinctBytes) {
		this.distinctBytes = distinctBytes;
		return this;
	}

	public long getOrphanObjects() {
		return orphanObjects;
	}

	public StorageReportResponse setOrphanObjects(long orphanObjects) {
		this.orphanObjects = orphanObjects;
		return this;
	}

	public long getOrphanBytes() {
		return orphanBytes;
	}

	public StorageReportResponse setOrphanBytes(long orphanBytes) {
		this.orphanBytes = orphanBytes;
		return this;
	}

	@Override
	public StorageReportResponse self() {
		return this;
	}
}
