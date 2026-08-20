package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.noderesult.NodeResultListResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

public interface NodeResultModelBuilder extends ModelBuilder, UserModelBuilder {

	default NodeResultResponse toNodeResultResponse(AssetNodeResult result) {
		NodeResultResponse response = new NodeResultResponse();
		response.setUuid(result.getUuid());
		if (result.getAssetUuid() != null) {
			response.setAssetUuid(result.getAssetUuid().toString());
		}
		response.setNodeKind(result.getNodeKind());
		response.setNodeId(result.getNodeId());
		response.setProducerVersion(result.getProducerVersion());
		response.setState(result.getState());
		response.setOrigin(result.getOrigin());
		response.setReason(result.getReason());
		response.setDurationMs(result.getDurationMs());
		response.setResultRef(result.getResultRef());
		if (result.getRunUuid() != null) {
			response.setRunUuid(result.getRunUuid().toString());
		}
		if (result.getTaskUuid() != null) {
			response.setTaskUuid(result.getTaskUuid().toString());
		}
		setStatus(result, response);
		return response;
	}

	default NodeResultListResponse toNodeResultList(List<AssetNodeResult> results) {
		NodeResultListResponse response = new NodeResultListResponse();
		for (AssetNodeResult result : results) {
			response.add(toNodeResultResponse(result));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) results.size());
		metainfo.setPerPage((long) results.size());
		response.setMetainfo(metainfo);
		return response;
	}

}
