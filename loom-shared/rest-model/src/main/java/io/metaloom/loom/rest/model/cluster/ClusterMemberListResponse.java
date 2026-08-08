package io.metaloom.loom.rest.model.cluster;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The members of one cluster.
 *
 * <p>
 * Not paged: a cluster is one subject within one asset, so its membership is bounded by how many times that subject was detected - tens, not
 * thousands. A reviewer also needs to see all of them at once to judge whether they really are the same person.
 * </p>
 */
public class ClusterMemberListResponse implements RestResponseModel<ClusterMemberListResponse> {

	private List<ClusterMemberModel> members = new ArrayList<>();

	private int total;

	public List<ClusterMemberModel> getMembers() {
		return members;
	}

	public ClusterMemberListResponse setMembers(List<ClusterMemberModel> members) {
		this.members = members;
		return this;
	}

	public ClusterMemberListResponse add(ClusterMemberModel member) {
		this.members.add(member);
		return this;
	}

	public int getTotal() {
		return total;
	}

	public ClusterMemberListResponse setTotal(int total) {
		this.total = total;
		return this;
	}

	@Override
	public ClusterMemberListResponse self() {
		return this;
	}

}
