package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.CREATE_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.READ_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_CLUSTER;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterConfirmRequest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterMemberCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Serves the cluster CRUD routes and the face-review loop (spec/workflows/WORKFLOW_FACE.md §4).
 *
 * <p>
 * A Cortex node proposes clusters per asset with status PENDING; a human confirms one into a person or rejects it. The proposing half and the deciding
 * half are separate permissions, so a reviewer can attribute faces without being able to write clusters, and a worker can write clusters without being
 * able to decide anything.
 * </p>
 */
@Singleton
public class ClusterEndpointService extends AbstractCRUDEndpointService<ClusterDao, Cluster> {

	/** Used when a create request omits the type; the column is NOT NULL. */
	private static final String DEFAULT_CLUSTER_TYPE = "generic";

	@Inject
	public ClusterEndpointService(ClusterDao clusterDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(clusterDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_CLUSTER, id);
	}

	/**
	 * {@code GET /api/v1/clusters?status=PENDING&type=face} - the review queue, keyset paged.
	 *
	 * <p>
	 * The filters are explicit query parameters rather than the generic filter machinery: the review queue is the one query this resource exists to
	 * answer, and an explicit parameter is what keeps it visible in the OpenAPI document and simple in every client.
	 * </p>
	 */
	@Override
	public void list(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_CLUSTER, () -> {
			String status = optionalStatus(firstQueryParam(lrc, "status"));
			String type = blankToNull(firstQueryParam(lrc, "type"));

			// pagingParams(), never lrc.pageSize() - the latter reads a path parameter and would ignore ?limit= entirely.
			PagingParameters paging = lrc.pagingParams();
			Page<Cluster> page = dao().loadPage(status, type, paging.from(), paging.limit());

			lrc.send(withMemberCounts(page));
		});
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_CLUSTER, () -> {
			return dao().load(id);
		}, cluster -> modelBuilder.toResponse(cluster, dao().countMembers(cluster.getUuid())));
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_CLUSTER, () -> {
			ClusterCreateRequest request = lrc.requestBody(ClusterCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String name = request.getName();
			// cluster.type is NOT NULL. The request may omit it, so fall back to the generic
			// kind rather than failing the insert.
			String type = request.getType() == null ? DEFAULT_CLUSTER_TYPE : request.getType();
			Cluster cluster = dao().createCluster(userUuid, name, type);
			update(request::getMeta, cluster::setMeta);
			return cluster;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_CLUSTER, () -> {
			ClusterUpdateRequest request = lrc.requestBody(ClusterUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Cluster cluster = dao().load(id);
			update(request::getName, cluster::setName);
			update(request::getType, cluster::setType);
			setEditor(cluster, userUuid);
			return cluster;
		}, modelBuilder::toResponse);
	}

	/** {@code GET /api/v1/clusters/:uuid/members} - the faces in a cluster, with the geometry needed to crop each one. */
	public void listMembers(LoomRoutingContext lrc, UUID clusterUuid) {
		checkPerm(lrc, READ_CLUSTER, () -> {
			requireCluster(clusterUuid);
			lrc.send(modelBuilder.toClusterMemberList(dao().listMembers(clusterUuid)));
		});
	}

	/**
	 * {@code POST /api/v1/clusters/:uuid/confirm} - this cluster is a particular person.
	 *
	 * <p>
	 * The required permissions depend on the request: linking an existing person needs {@code UPDATE_CLUSTER}, while creating one also needs
	 * {@code CREATE_PERSON}. That lets a reviewer be trusted to attribute faces to known people without being able to add new ones to the directory.
	 * </p>
	 */
	public void confirm(LoomRoutingContext lrc, UUID clusterUuid) {
		ClusterConfirmRequest request = lrc.requestBody(ClusterConfirmRequest.class);
		UUID personUuid = optionalUuid(request.getPersonUuid(), "personUuid");
		boolean createsPerson = personUuid == null;

		Permission[] required = createsPerson
			? new Permission[] { UPDATE_CLUSTER, CREATE_PERSON }
			: new Permission[] { UPDATE_CLUSTER };

		checkPerms(lrc, () -> {
			requireCluster(clusterUuid);
			if (createsPerson && isBlank(request.getAlias()) && isBlank(request.getFirstname()) && isBlank(request.getLastname())) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"Confirming without a personUuid creates a person, which needs at least an alias, firstname or lastname.");
			}
			if (personUuid != null && daos().personDao().load(personUuid) == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Person not found.");
			}

			ClusterDao.PersonDraft draft = createsPerson
				? new ClusterDao.PersonDraft(request.getAlias(), request.getFirstname(), request.getLastname())
				: null;
			Cluster confirmed = dao().confirm(clusterUuid, personUuid, draft, lrc.userUuid());

			// An optional label for the grouping itself, distinct from the person's name. Applied after the verdict so a failure to name cannot
			// leave the cluster unconfirmed.
			if (!isBlank(request.getName())) {
				confirmed.setName(request.getName());
				dao().update(confirmed);
			}
			lrc.send(modelBuilder.toResponse(confirmed, dao().countMembers(clusterUuid)));
		}, required);
	}

	/**
	 * {@code POST /api/v1/clusters/:uuid/reject} - this cluster is not a subject worth keeping.
	 *
	 * <p>
	 * The person pointer is deliberately left as it is. Rejecting something previously confirmed is a correction, and keeping the pointer preserves
	 * what the earlier verdict was; the status is what says the cluster is no longer to be acted on.
	 * </p>
	 */
	public void reject(LoomRoutingContext lrc, UUID clusterUuid) {
		checkPerm(lrc, UPDATE_CLUSTER, () -> {
			requireCluster(clusterUuid);
			Cluster rejected = dao().updateStatus(clusterUuid, Cluster.STATUS_REJECTED, null, lrc.userUuid());
			lrc.send(modelBuilder.toResponse(rejected, dao().countMembers(clusterUuid)));
		});
	}

	/** {@code GET /api/v1/assets/:uuid/clusters} - the clusters computed within one asset. */
	public void listAssetClusters(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_CLUSTER, () -> {
			List<Cluster> clusters = dao().listByAsset(assetUuid);
			Map<UUID, Long> counts = dao().countMembers(clusters.stream().map(Cluster::getUuid).toList());
			ClusterListResponse response = new ClusterListResponse();
			clusters.forEach(cluster -> response.add(modelBuilder.toResponse(cluster, counts.getOrDefault(cluster.getUuid(), 0L))));
			lrc.send(response);
		});
	}

	/**
	 * {@code POST /api/v1/assets/:uuid/clusters/bulk} - every cluster a producer found in one asset.
	 *
	 * <p>
	 * Idempotent on {@code (asset, nodeKind, clusterIndex)}, and it never overwrites a review verdict: re-running the producing node rewrites the
	 * geometry of its own proposals and leaves alone what a human decided about them.
	 * </p>
	 */
	public void bulkCreateAssetClusters(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, CREATE_CLUSTER, () -> {
			ClusterBulkCreateRequest request = lrc.requestBody(ClusterBulkCreateRequest.class);
			List<ClusterCreateItem> items = request.getClusters() == null ? List.of() : request.getClusters();

			// Validate everything before writing anything: a bad member uuid halfway through would otherwise leave the asset holding a partial set of
			// subjects, which is worse than holding the previous one.
			String nodeKind = null;
			Set<Integer> indices = new HashSet<>();
			for (ClusterCreateItem item : items) {
				if (isBlank(item.getNodeKind())) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Every cluster needs a nodeKind.");
				}
				if (item.getClusterIndex() == null) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Every cluster needs a clusterIndex, which is its upsert key.");
				}
				if (!indices.add(item.getClusterIndex())) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
						"Duplicate clusterIndex " + item.getClusterIndex() + " - the index identifies the cluster within the asset.");
				}
				if (nodeKind == null) {
					nodeKind = item.getNodeKind();
				} else if (!nodeKind.equals(item.getNodeKind())) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
						"One bulk write describes one producer's view of the asset, so every cluster must carry the same nodeKind.");
				}
				for (ClusterMemberCreateItem member : item.getMembers()) {
					if (optionalUuid(member.getEmbeddingUuid(), "members.embeddingUuid") == null) {
						throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Every cluster member needs an embeddingUuid.");
					}
				}
			}

			ClusterBulkResponse response = new ClusterBulkResponse();
			List<UUID> stored = new ArrayList<>();
			for (ClusterCreateItem item : items) {
				Cluster cluster = dao().createMachineCluster(
					item.getType() == null ? Cluster.TYPE_FACE : item.getType(),
					item.getNodeKind(), assetUuid, item.getClusterIndex());
				cluster.setNodeId(item.getNodeId());
				cluster.setProducerVersion(item.getProducerVersion() == null ? "" : item.getProducerVersion());
				cluster.setScore(item.getScore());
				cluster.setCentroid(item.getCentroid());
				cluster.setModel(item.getModel());
				cluster.setDimensions(item.getDimensions());
				cluster.setMeta(item.getMeta());
				dao().upsertCluster(cluster);
				stored.add(cluster.getUuid());

				// Replace the membership rather than adding to it: a re-run that moved a face between subjects must not leave it in both.
				dao().unlinkAll(cluster.getUuid());
				for (ClusterMemberCreateItem member : item.getMembers()) {
					dao().link(cluster.getUuid(), UUID.fromString(member.getEmbeddingUuid()), member.getConfidence(), member.getOrigin());
				}
				response.add(modelBuilder.toResponse(dao().load(cluster.getUuid()), (long) item.getMembers().size()));
			}

			if (request.pruneStaleOrDefault() && nodeKind != null) {
				response.setPruned(dao().deleteStalePending(assetUuid, nodeKind, indices));
			}
			response.setTotal(items.size()).setCreated(stored.size()).setFailed(0);
			lrc.send(response, 201);
		});
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Attach the member count to every cluster on the page with one query rather than one per card.
	 */
	private ClusterListResponse withMemberCounts(Page<Cluster> page) {
		List<UUID> uuids = new ArrayList<>();
		page.forEach(cluster -> uuids.add(cluster.getUuid()));
		Map<UUID, Long> counts = dao().countMembers(uuids);

		return modelBuilder.setPage(new ClusterListResponse(), page,
			cluster -> modelBuilder.toResponse(cluster, counts.getOrDefault(cluster.getUuid(), 0L)));
	}

	private Cluster requireCluster(UUID uuid) {
		Cluster cluster = dao().load(uuid);
		if (cluster == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Cluster not found.");
		}
		return cluster;
	}

	private static String firstQueryParam(LoomRoutingContext lrc, String name) {
		List<String> values = lrc.queryParam(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String optionalStatus(String raw) {
		String status = blankToNull(raw);
		if (status == null) {
			return null;
		}
		if (Cluster.STATUS_PENDING.equals(status) || Cluster.STATUS_CONFIRMED.equals(status) || Cluster.STATUS_REJECTED.equals(status)) {
			return status;
		}
		throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The status must be one of PENDING, CONFIRMED, REJECTED.");
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
