package io.metaloom.loom.rest.model.dedup;

import java.util.List;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * A dedup review group with its members.
 */
public class DedupGroupResponse implements RestResponseModel<DedupGroupResponse> {

	private String uuid;
	private String algorithm;
	private String status;
	private String keepAssetUuid;
	private Float score;
	private List<DedupGroupMemberModel> members;

	public String getUuid() {
		return uuid;
	}

	public DedupGroupResponse setUuid(String uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public DedupGroupResponse setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	public String getStatus() {
		return status;
	}

	public DedupGroupResponse setStatus(String status) {
		this.status = status;
		return this;
	}

	public String getKeepAssetUuid() {
		return keepAssetUuid;
	}

	public DedupGroupResponse setKeepAssetUuid(String keepAssetUuid) {
		this.keepAssetUuid = keepAssetUuid;
		return this;
	}

	public Float getScore() {
		return score;
	}

	public DedupGroupResponse setScore(Float score) {
		this.score = score;
		return this;
	}

	public List<DedupGroupMemberModel> getMembers() {
		return members;
	}

	public DedupGroupResponse setMembers(List<DedupGroupMemberModel> members) {
		this.members = members;
		return this;
	}

	@Override
	public DedupGroupResponse self() {
		return this;
	}
}
