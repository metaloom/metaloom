package io.metaloom.loom.rest.model.storage;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * One place this deployment writes bytes to, and how full it is.
 *
 * <p>
 * 🔴 {@code freeBytes} and {@code totalBytes} are null for an object store, which has no capacity to report. That is why {@code watermark} carries
 * {@code UNKNOWN} rather than {@code OK}: a bucket is not known to be healthy, it is unmeasurable, and a dashboard that paints it green is answering a
 * question nobody could ask.
 * </p>
 */
public class StorageBackendModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The storage pool, or null for the deployment's default local storage.")
	private UUID poolUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("A human-readable name for this backend.")
	private String poolName;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The backend kind: 'filesystem' or 's3'.")
	private String kind;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Where this backend points. Never contains credentials. Null when the backend could not be built.")
	private String description;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Usable space in bytes, or null when the backend cannot say - which an object store always is.")
	private Long freeBytes;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Capacity in bytes, or null for the same reason as freeBytes.")
	private Long totalBytes;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How close to full: OK, WARN, CRITICAL, or UNKNOWN when the backend reports no capacity. Uploads are refused at CRITICAL.")
	private String watermark;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many distinct stored objects this backend holds, according to the catalogue.")
	private long objects;

	@JsonProperty(required = true)
	@JsonPropertyDescription("What those objects occupy in bytes, according to the catalogue.")
	private long bytes;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Why this backend could not be reached, or null when it could. A misconfigured pool is reported here rather than failing the whole report.")
	private String error;

	public StorageBackendModel() {
	}

	public UUID getPoolUuid() {
		return poolUuid;
	}

	public StorageBackendModel setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	public String getPoolName() {
		return poolName;
	}

	public StorageBackendModel setPoolName(String poolName) {
		this.poolName = poolName;
		return this;
	}

	public String getKind() {
		return kind;
	}

	public StorageBackendModel setKind(String kind) {
		this.kind = kind;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public StorageBackendModel setDescription(String description) {
		this.description = description;
		return this;
	}

	public Long getFreeBytes() {
		return freeBytes;
	}

	public StorageBackendModel setFreeBytes(Long freeBytes) {
		this.freeBytes = freeBytes;
		return this;
	}

	public Long getTotalBytes() {
		return totalBytes;
	}

	public StorageBackendModel setTotalBytes(Long totalBytes) {
		this.totalBytes = totalBytes;
		return this;
	}

	public String getWatermark() {
		return watermark;
	}

	public StorageBackendModel setWatermark(String watermark) {
		this.watermark = watermark;
		return this;
	}

	public long getObjects() {
		return objects;
	}

	public StorageBackendModel setObjects(long objects) {
		this.objects = objects;
		return this;
	}

	public long getBytes() {
		return bytes;
	}

	public StorageBackendModel setBytes(long bytes) {
		this.bytes = bytes;
		return this;
	}

	public String getError() {
		return error;
	}

	public StorageBackendModel setError(String error) {
		this.error = error;
		return this;
	}
}
