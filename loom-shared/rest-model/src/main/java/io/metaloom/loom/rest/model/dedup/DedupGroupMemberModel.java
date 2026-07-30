package io.metaloom.loom.rest.model.dedup;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One member of a dedup group: an asset playing the KEEP or a DUP role, with its similarity score to the KEEP and discovery-time size/completeness
 * snapshots. Used both in create requests and in responses.
 */
public class DedupGroupMemberModel implements RestResponseModel<DedupGroupMemberModel> {

	/** The asset to keep. */
	public static final String ROLE_KEEP = "KEEP";

	/** A candidate duplicate the apply node may move once confirmed. */
	public static final String ROLE_DUP = "DUP";

	private String assetUuid;
	private String role;
	private Float score;
	private Long size;
	private Long zeroChunkCount;

	public String getAssetUuid() {
		return assetUuid;
	}

	public DedupGroupMemberModel setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getRole() {
		return role;
	}

	public DedupGroupMemberModel setRole(String role) {
		this.role = role;
		return this;
	}

	public Float getScore() {
		return score;
	}

	public DedupGroupMemberModel setScore(Float score) {
		this.score = score;
		return this;
	}

	public Long getSize() {
		return size;
	}

	public DedupGroupMemberModel setSize(Long size) {
		this.size = size;
		return this;
	}

	public Long getZeroChunkCount() {
		return zeroChunkCount;
	}

	public DedupGroupMemberModel setZeroChunkCount(Long zeroChunkCount) {
		this.zeroChunkCount = zeroChunkCount;
		return this;
	}

	@Override
	public DedupGroupMemberModel self() {
		return this;
	}
}
