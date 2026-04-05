package io.metaloom.loom.rest.model.processor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * System status information reported by a processor node.
 */
public class SystemStatusInfo implements RestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("CPU load percentage (0-100)")
	private Double cpuLoad;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Used memory in bytes")
	private Long memoryUsed;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Total memory in bytes")
	private Long memoryTotal;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Used disk space in bytes")
	private Long diskUsed;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Total disk space in bytes")
	private Long diskTotal;

	public Double getCpuLoad() {
		return cpuLoad;
	}

	public SystemStatusInfo setCpuLoad(Double cpuLoad) {
		this.cpuLoad = cpuLoad;
		return this;
	}

	public Long getMemoryUsed() {
		return memoryUsed;
	}

	public SystemStatusInfo setMemoryUsed(Long memoryUsed) {
		this.memoryUsed = memoryUsed;
		return this;
	}

	public Long getMemoryTotal() {
		return memoryTotal;
	}

	public SystemStatusInfo setMemoryTotal(Long memoryTotal) {
		this.memoryTotal = memoryTotal;
		return this;
	}

	public Long getDiskUsed() {
		return diskUsed;
	}

	public SystemStatusInfo setDiskUsed(Long diskUsed) {
		this.diskUsed = diskUsed;
		return this;
	}

	public Long getDiskTotal() {
		return diskTotal;
	}

	public SystemStatusInfo setDiskTotal(Long diskTotal) {
		this.diskTotal = diskTotal;
		return this;
	}
}
