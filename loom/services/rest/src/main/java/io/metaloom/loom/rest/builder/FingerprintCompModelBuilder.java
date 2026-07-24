package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompListResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompResponse;

public interface FingerprintCompModelBuilder extends ModelBuilder, UserModelBuilder {

	default FingerprintCompResponse toFingerprintCompResponse(AssetFingerprintComp comp) {
		FingerprintCompResponse response = new FingerprintCompResponse();
		response.setUuid(comp.getUuid());
		if (comp.getAssetUuid() != null) {
			response.setAssetUuid(comp.getAssetUuid().toString());
		}
		response.setNodeKind(comp.getNodeKind());
		response.setAlgorithm(comp.getAlgorithm());
		response.setSectorIndex(comp.getSectorIndex());
		response.setTimeFrom(comp.getTimeFrom());
		response.setTimeTo(comp.getTimeTo());
		response.setFingerprint(comp.getFingerprint());
		response.setProducerVersion(comp.getProducerVersion());
		setStatus(comp, response);
		return response;
	}

	default FingerprintCompListResponse toFingerprintCompList(List<AssetFingerprintComp> comps) {
		FingerprintCompListResponse response = new FingerprintCompListResponse();
		for (AssetFingerprintComp comp : comps) {
			response.add(toFingerprintCompResponse(comp));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) comps.size());
		metainfo.setPerPage((long) comps.size());
		response.setMetainfo(metainfo);
		return response;
	}

}
