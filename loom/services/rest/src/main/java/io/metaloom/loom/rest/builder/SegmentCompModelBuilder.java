package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.asset.AssetSegmentComp;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompListResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;

public interface SegmentCompModelBuilder extends ModelBuilder, UserModelBuilder {

	default SegmentCompResponse toSegmentCompResponse(AssetSegmentComp comp) {
		SegmentCompResponse response = new SegmentCompResponse();
		response.setUuid(comp.getUuid());
		if (comp.getAssetUuid() != null) {
			response.setAssetUuid(comp.getAssetUuid().toString());
		}
		response.setNodeKind(comp.getNodeKind());
		response.setSegmentType(comp.getSegmentType());
		response.setProducerVersion(comp.getProducerVersion());
		response.setSeq(comp.getSeq());
		response.setTimeFrom(comp.getTimeFrom());
		response.setTimeTo(comp.getTimeTo());
		response.setTitle(comp.getTitle());
		response.setScore(comp.getScore());
		setStatus(comp, response);
		return response;
	}

	default SegmentCompListResponse toSegmentCompList(List<AssetSegmentComp> comps) {
		SegmentCompListResponse response = new SegmentCompListResponse();
		for (AssetSegmentComp comp : comps) {
			response.add(toSegmentCompResponse(comp));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) comps.size());
		metainfo.setPerPage((long) comps.size());
		response.setMetainfo(metainfo);
		return response;
	}

}
