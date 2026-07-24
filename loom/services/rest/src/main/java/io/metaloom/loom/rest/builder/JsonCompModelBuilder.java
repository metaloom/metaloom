package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompListResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

public interface JsonCompModelBuilder extends ModelBuilder, UserModelBuilder {

	default JsonCompResponse toJsonCompResponse(AssetJsonComp comp) {
		JsonCompResponse response = new JsonCompResponse();
		response.setUuid(comp.getUuid());
		if (comp.getAssetUuid() != null) {
			response.setAssetUuid(comp.getAssetUuid().toString());
		}
		response.setNodeKind(comp.getNodeKind());
		response.setSchemaType(comp.getSchemaType());
		response.setVariant(comp.getVariant());
		response.setProducerVersion(comp.getProducerVersion());
		response.setData(comp.getData());
		setStatus(comp, response);
		return response;
	}

	default JsonCompListResponse toJsonCompList(List<AssetJsonComp> comps) {
		JsonCompListResponse response = new JsonCompListResponse();
		for (AssetJsonComp comp : comps) {
			response.add(toJsonCompResponse(comp));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) comps.size());
		metainfo.setPerPage((long) comps.size());
		response.setMetainfo(metainfo);
		return response;
	}

}
