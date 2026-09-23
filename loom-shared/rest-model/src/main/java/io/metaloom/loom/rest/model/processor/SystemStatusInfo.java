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
	@JsonPropertyDescription("GPU load percentage (0-100)")
	private Double gpuLoad;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Used GPU memory in bytes, summed over every visible device")
	private Long gpuMemoryUsed;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Total GPU memory in bytes, summed over every visible device")
	private Long gpuMemoryTotal;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Model name of the GPU, or a count and the first name when the worker has several")
	private String gpuName;

	@JsonProperty(required = false)
	@JsonPropertyDescription("I/O load percentage (0-100)")
	private Double ioLoad;

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

	public Double getGpuLoad() {
		return gpuLoad;
	}

	public SystemStatusInfo setGpuLoad(Double gpuLoad) {
		this.gpuLoad = gpuLoad;
		return this;
	}

	/**
	 * How much video memory is in use, in bytes.
	 *
	 * <p>
	 * Reported separately from {@link #getGpuLoad() utilisation} because the two say different things and a worker can be at either extreme of one
	 * while at the other extreme of the other: a model sitting resident in VRAM between jobs pins memory at 90% while the card is idle, and that is
	 * exactly the state where a second model will not fit. Placement cares about the memory; a person watching the fleet cares about both.
	 * </p>
	 */
	public Long getGpuMemoryUsed() {
		return gpuMemoryUsed;
	}

	public SystemStatusInfo setGpuMemoryUsed(Long gpuMemoryUsed) {
		this.gpuMemoryUsed = gpuMemoryUsed;
		return this;
	}

	public Long getGpuMemoryTotal() {
		return gpuMemoryTotal;
	}

	public SystemStatusInfo setGpuMemoryTotal(Long gpuMemoryTotal) {
		this.gpuMemoryTotal = gpuMemoryTotal;
		return this;
	}

	public String getGpuName() {
		return gpuName;
	}

	public SystemStatusInfo setGpuName(String gpuName) {
		this.gpuName = gpuName;
		return this;
	}

	public Double getIoLoad() {
		return ioLoad;
	}

	public SystemStatusInfo setIoLoad(Double ioLoad) {
		this.ioLoad = ioLoad;
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
