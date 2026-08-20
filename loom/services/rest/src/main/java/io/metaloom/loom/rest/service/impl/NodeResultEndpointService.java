package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.asset.AssetNodeResultDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultListResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Endpoint service for the per-asset processing ledger ({@code asset_node_result}).
 *
 * <p>
 * This is the node-agnostic companion to the typed component endpoints (e.g. transcripts): after a cortex node writes its payload it records a ledger
 * row here so the "has node X run on asset A" question can be answered without inspecting every payload table.
 * </p>
 */
@Singleton
public class NodeResultEndpointService extends AbstractEndpointService {

	private final AssetNodeResultDao nodeResultDao;
	private final io.metaloom.loom.common.metrics.LoomMetrics metrics;

	@Inject
	public NodeResultEndpointService(AssetNodeResultDao nodeResultDao, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		io.metaloom.loom.common.metrics.LoomMetrics metrics) {
		super(modelBuilder, validator);
		this.nodeResultDao = nodeResultDao;
		this.metrics = metrics;
	}

	public void createAssetNodeResult(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			NodeResultCreateRequest request = lrc.requestBody(NodeResultCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String nodeId = request.getNodeId() == null ? "" : request.getNodeId();
			AssetNodeResult result = nodeResultDao.createNodeResult(userUuid, assetUuid, request.getNodeKind(), nodeId);
			if (request.getProducerVersion() != null) {
				result.setProducerVersion(request.getProducerVersion());
			}
			result.setState(request.getState());
			if (request.getOrigin() != null) {
				result.setOrigin(request.getOrigin());
			}
			if (request.getReason() != null) {
				result.setReason(request.getReason());
			}
			if (request.getDurationMs() != null) {
				result.setDurationMs(request.getDurationMs());
			}
			if (request.getResultRef() != null) {
				result.setResultRef(request.getResultRef());
			}
			// Both are ON DELETE SET NULL - a pruned run detaches the ledger row instead of deleting it,
			// so storing the references costs nothing when the run is later removed.
			if (request.getRunUuid() != null) {
				result.setRunUuid(UUID.fromString(request.getRunUuid()));
			}
			if (request.getTaskUuid() != null) {
				result.setTaskUuid(UUID.fromString(request.getTaskUuid()));
			}
			// Upsert on (asset_uuid, node_kind, node_id): re-running a node rewrites its single ledger row.
			nodeResultDao.upsert(result);
			metrics.recordAssetNodeResultWritten(
				request.getNodeKind() == null ? "unknown" : request.getNodeKind(),
				request.getState() == null ? "unknown" : String.valueOf(request.getState()).toLowerCase());
			NodeResultResponse response = modelBuilder.toNodeResultResponse(result);
			lrc.send(response, 201);
		});
	}

	public void listAssetNodeResults(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			List<AssetNodeResult> results = nodeResultDao.loadByAsset(assetUuid);
			NodeResultListResponse response = modelBuilder.toNodeResultList(results);
			lrc.send(response);
		});
	}

	public void loadAssetNodeResult(LoomRoutingContext lrc, UUID assetUuid, UUID nodeResultUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			AssetNodeResult result = nodeResultDao.load(nodeResultUuid);
			if (result == null || !assetUuid.equals(result.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Node result not found.");
			}
			NodeResultResponse response = modelBuilder.toNodeResultResponse(result);
			lrc.send(response);
		});
	}

	public void deleteAssetNodeResult(LoomRoutingContext lrc, UUID assetUuid, UUID nodeResultUuid) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetNodeResult result = nodeResultDao.load(nodeResultUuid);
			if (result == null || !assetUuid.equals(result.getAssetUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Node result not found.");
			}
			nodeResultDao.delete(nodeResultUuid);
			lrc.sendNoContent();
		});
	}

}
