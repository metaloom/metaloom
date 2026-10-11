package io.metaloom.loom.rest.builder;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
		response.setModel(cluster.getModel());
		response.setSuggestedPersonUuid(cluster.getSuggestedPersonUuid() == null ? null : cluster.getSuggestedPersonUuid().toString());
		response.setSuggestedScore(cluster.getSuggestedScore());
		// setStatus is the creator/editor audit block, not the review verdict - the two names collide on this
		// response, which is why the verdict above is called reviewStatus.
		setStatus(cluster, response);
		return response;
	}

	/** As {@link #toResponse(Cluster)}, with the member count the review queue renders on each card. */
	default ClusterResponse toResponse(Cluster cluster, Long memberCount) {
		return toResponse(cluster).setMemberCount(memberCount);
	}

	/**
	 * Fill in the names of the confirmed and the suggested person on each response.
	 *
	 * <p>
	 * Separate from {@link #toResponse(Cluster)} because a name is not on the cluster row: the caller loads every name a page needs with one query
	 * ({@link #personUuidsOf(Collection)} says which) and hands the map in, rather than the builder asking once per card.
	 * </p>
	 */
	default <C extends Collection<ClusterResponse>> C withPersonNames(C responses, Map<UUID, String> names) {
		for (ClusterResponse response : responses) {
			response.setPersonName(nameOf(response.getPersonUuid(), names));
			response.setSuggestedPersonName(nameOf(response.getSuggestedPersonUuid(), names));
		}
		return responses;
	}

	/** Every person a set of responses refers to, confirmed or suggested. */
	static Set<UUID> personUuidsOf(Collection<ClusterResponse> responses) {
		Set<UUID> uuids = new LinkedHashSet<>();
		for (ClusterResponse response : responses) {
			if (response.getPersonUuid() != null) {
				uuids.add(UUID.fromString(response.getPersonUuid()));
			}
			if (response.getSuggestedPersonUuid() != null) {
				uuids.add(UUID.fromString(response.getSuggestedPersonUuid()));
			}
		}
		return uuids;
	}

	private static String nameOf(String personUuid, Map<UUID, String> names) {
		return personUuid == null || names == null ? null : names.get(UUID.fromString(personUuid));
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
			.setTimeFrom(member.getTimeFrom())
			.setTimeTo(member.getTimeTo())
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
