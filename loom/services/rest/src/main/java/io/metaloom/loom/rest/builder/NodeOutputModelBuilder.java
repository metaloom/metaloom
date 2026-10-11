package io.metaloom.loom.rest.builder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.db.model.asset.AssetNodeOutput;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputListResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputResponse;

public interface NodeOutputModelBuilder extends ModelBuilder {

	default NodeOutputResponse toNodeOutputResponse(AssetNodeOutput output) {
		return new NodeOutputResponse()
			.setUuid(output.uuid().toString())
			.setAssetUuid(output.assetUuid().toString())
			.setNodeKind(output.nodeKind())
			.setReuseKey(output.reuseKey())
			.setNodeId(output.nodeId())
			.setProducerVersion(output.producerVersion())
			.setState(output.state())
			.setReason(output.reason())
			.setOutputs(output.outputs())
			.setRunUuid(output.runUuid() == null ? null : output.runUuid().toString())
			.setCreated(isoOrNull(output.created()))
			.setEdited(isoOrNull(output.edited()));
	}

	default NodeOutputListResponse toNodeOutputList(List<AssetNodeOutput> outputs) {
		NodeOutputListResponse response = new NodeOutputListResponse();
		// "data" is a required field: an asset with nothing stored answers [], not a missing array.
		response.setData(new ArrayList<>(outputs.size()));
		for (AssetNodeOutput output : outputs) {
			response.add(toNodeOutputResponse(output));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) outputs.size());
		metainfo.setPerPage((long) outputs.size());
		response.setMetainfo(metainfo);
		return response;
	}

	private static String isoOrNull(Instant instant) {
		return instant == null ? null : instant.toString();
	}

}
