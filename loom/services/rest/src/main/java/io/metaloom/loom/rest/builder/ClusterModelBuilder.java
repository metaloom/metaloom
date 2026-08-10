package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterMember;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterMemberListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterMemberModel;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;

public interface ClusterModelBuilder extends UserModelBuilder {

	default ClusterResponse toResponse(Cluster cluster) {
		ClusterResponse response = new ClusterResponse();
		response.setUuid(cluster.getUuid());
		response.setName(cluster.getName());
		response.setType(cluster.getType());
		response.setMeta(cluster.getMeta());
		response.setReviewStatus(cluster.getStatus());
		response.setPersonUuid(cluster.getPersonUuid() == null ? null : cluster.getPersonUuid().toString());
		response.setReviewedAt(cluster.getReviewedAt() == null ? null : cluster.getReviewedAt().toString());
		response.setReviewerUuid(cluster.getReviewerUuid() == null ? null : cluster.getReviewerUuid().toString());
		response.setAssetUuid(cluster.getAssetUuid() == null ? null : cluster.getAssetUuid().toString());
		response.setClusterIndex(cluster.getClusterIndex());
		response.setScore(cluster.getScore());
		response.setNodeKind(cluster.getNodeKind());
		// setStatus is the creator/editor audit block, not the review verdict - the two names collide on this
		// response, which is why the verdict above is called reviewStatus.
		setStatus(cluster, response);
		return response;
	}

	/** As {@link #toResponse(Cluster)}, with the member count the review queue renders on each card. */
	default ClusterResponse toResponse(Cluster cluster, Long memberCount) {
		return toResponse(cluster).setMemberCount(memberCount);
	}

	default ClusterListResponse toClusterList(Page<Cluster> page) {
		return setPage(new ClusterListResponse(), page, this::toResponse);
	}

	default ClusterMemberModel toResponse(ClusterMember member) {
		return new ClusterMemberModel()
			.setEmbeddingUuid(member.getEmbeddingUuid() == null ? null : member.getEmbeddingUuid().toString())
			.setDetectionUuid(member.getDetectionUuid() == null ? null : member.getDetectionUuid().toString())
			.setAssetUuid(member.getAssetUuid() == null ? null : member.getAssetUuid().toString())
			.setFrameNumber(member.getFrameNumber())
			.setBboxX(member.getBboxX())
			.setBboxY(member.getBboxY())
			.setBboxWidth(member.getBboxWidth())
			.setBboxHeight(member.getBboxHeight())
			.setConfidence(member.getConfidence())
			.setOrigin(member.getOrigin());
	}

	default ClusterMemberListResponse toClusterMemberList(List<ClusterMember> members) {
		ClusterMemberListResponse response = new ClusterMemberListResponse();
		members.forEach(member -> response.add(toResponse(member)));
		return response.setTotal(members.size());
	}

}
