package io.metaloom.loom.rest.model.asset.info;

public class ConsistencyInfo {

	private Long zeroChunkCount;

	public Long getZeroChunkCount() {
		return zeroChunkCount;
	}

	public ConsistencyInfo setZeroChunkCount(Long count) {
		this.zeroChunkCount = count;
		return this;
	}

}
