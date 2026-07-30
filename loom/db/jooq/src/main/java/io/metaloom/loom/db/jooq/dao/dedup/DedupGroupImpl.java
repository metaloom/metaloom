package io.metaloom.loom.db.jooq.dao.dedup;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.dedup.DedupGroup;

public class DedupGroupImpl extends AbstractEditableElement<DedupGroup> implements DedupGroup {

	private String algorithm;
	private String status = STATUS_PENDING;
	private UUID keepAssetUuid;
	private Float score;

	@Override
	public String getAlgorithm() {
		return algorithm;
	}

	@Override
	public DedupGroup setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	@Override
	public String getStatus() {
		return status;
	}

	@Override
	public DedupGroup setStatus(String status) {
		this.status = status;
		return this;
	}

	@Override
	public UUID getKeepAssetUuid() {
		return keepAssetUuid;
	}

	@Override
	public DedupGroup setKeepAssetUuid(UUID keepAssetUuid) {
		this.keepAssetUuid = keepAssetUuid;
		return this;
	}

	@Override
	public Float getScore() {
		return score;
	}

	@Override
	public DedupGroup setScore(Float score) {
		this.score = score;
		return this;
	}
}
