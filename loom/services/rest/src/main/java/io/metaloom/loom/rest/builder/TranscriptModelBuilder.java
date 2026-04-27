package io.metaloom.loom.rest.builder;

import java.util.List;

import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.transcript.TranscriptListResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;

public interface TranscriptModelBuilder extends ModelBuilder, UserModelBuilder {

	default TranscriptResponse toTranscriptResponse(AssetTranscriptComp comp) {
		TranscriptResponse response = new TranscriptResponse();
		response.setUuid(comp.getUuid());
		response.setSource(comp.getSource());
		response.setLang(comp.getLang());
		response.setTranscriptText(comp.getTranscriptText());
		response.setDuration(comp.getDuration());
		response.setModel(comp.getModel());
		response.setTranscriptJson(comp.getTranscriptJson());
		if (comp.getAssetUuid() != null) {
			response.setAssetUuid(comp.getAssetUuid().toString());
		}
		setStatus(comp, response);
		return response;
	}

	default TranscriptListResponse toTranscriptList(List<AssetTranscriptComp> comps) {
		TranscriptListResponse response = new TranscriptListResponse();
		for (AssetTranscriptComp comp : comps) {
			response.add(toTranscriptResponse(comp));
		}
		PagingInfo metainfo = new PagingInfo();
		metainfo.setTotalCount((long) comps.size());
		metainfo.setPerPage((long) comps.size());
		response.setMetainfo(metainfo);
		return response;
	}

}
