package io.metaloom.loom.rest.model.dedup;

import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Create/upsert request for a dedup review group. Emitted by the discovery node. The server upserts on {@code (keepAssetUuid, algorithm)} among
 * PENDING groups so re-running discovery over the same content updates the same review record instead of duplicating it.
 */
public class DedupGroupCreateRequest implements RestRequestModel {

	private String algorithm;
	private String keepAssetUuid;
	private Float score;
	private List<DedupGroupMemberModel> members;

	public String getAlgorithm() {
		return algorithm;
	}

	public DedupGroupCreateRequest setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	public String getKeepAssetUuid() {
		return keepAssetUuid;
	}

	public DedupGroupCreateRequest setKeepAssetUuid(String keepAssetUuid) {
		this.keepAssetUuid = keepAssetUuid;
		return this;
	}

	public Float getScore() {
		return score;
	}

	public DedupGroupCreateRequest setScore(Float score) {
		this.score = score;
		return this;
	}

	public List<DedupGroupMemberModel> getMembers() {
		return members;
	}

	public DedupGroupCreateRequest setMembers(List<DedupGroupMemberModel> members) {
		this.members = members;
		return this;
	}
}
