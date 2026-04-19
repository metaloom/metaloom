package io.metaloom.loom.rest.builder;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.rest.model.asset.AssetComponentListResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.metaloom.loom.rest.model.asset.info.TranscriptInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;

public interface AssetComponentModelBuilder extends ModelBuilder, UserModelBuilder {

	default AssetComponentResponse toResponse(AssetGeoComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.GEO);
		response.setGeo(new GeoLocationInfo()
			.setSource(comp.getSource())
			.setLon(comp.getGeoLon())
			.setLat(comp.getGeoLat())
			.setAlias(comp.getGeoAlias()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetImageComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.IMAGE);
		response.setImage(new ImageInfo()
			.setSource(comp.getSource())
			.setDominantColor(comp.getImageDominantColor())
			.setWidth(comp.getMediaWidth())
			.setHeight(comp.getMediaHeight()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetVideoComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.VIDEO);
		response.setVideo(new VideoInfo()
			.setSource(comp.getSource())
			.setBitrate(comp.getVideoBitrate())
			.setEncoding(comp.getVideoEncoding())
			.setWidth(comp.getMediaWidth())
			.setHeight(comp.getMediaHeight())
			.setDuration(comp.getMediaDuration()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetAudioComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.AUDIO);
		response.setAudio(new AudioInfo()
			.setSource(comp.getSource())
			.setBpm(comp.getAudioBpm())
			.setBitrate(comp.getAudioBitrate())
			.setChannels(comp.getAudioChannels())
			.setEncoding(comp.getAudioEncoding())
			.setSamplingRate(comp.getAudioSamplingRate())
			.setDuration(comp.getMediaDuration()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetDocComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.DOC);
		response.setDocument(new DocumentInfo()
			.setSource(comp.getSource())
			.setWordCount(comp.getDocWordCount() != null ? comp.getDocWordCount().longValue() : null)
			.setPlainText(comp.getDocPlainText()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetTranscriptComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.TRANSCRIPT);
		response.setTranscript(new TranscriptInfo()
			.setSource(comp.getSource())
			.setLang(comp.getLang())
			.setTranscriptText(comp.getTranscriptText())
			.setDuration(comp.getDuration())
			.setModel(comp.getModel())
			.setTranscriptJson(comp.getTranscriptJson()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetJsonComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.JSON);
		response.setJson(new JsonComponentInfo()
			.setSchemaType(comp.getSchemaType())
			.setData(comp.getData()));
		return response;
	}

	default void setCommon(AssetComponentResponse response, AssetComponent<?> comp, AssetComponentType type) {
		response.setUuid(comp.getUuid());
		response.setType(type);
		response.setAssetUuid(comp.getAssetUuid());
		response.setSource(comp.getSource());
		setStatus(comp, response);
	}

	default AssetComponentListResponse toComponentList(UUID assetUuid) {
		AssetComponentListResponse response = new AssetComponentListResponse();
		AssetComponentDao compDao = daos().assetComponentDao();

		for (AssetGeoComp c : compDao.loadGeoComps(assetUuid)) {
			response.add(toResponse(c));
		}
		for (AssetImageComp c : compDao.loadImageComps(assetUuid)) {
			response.add(toResponse(c));
		}
		for (AssetVideoComp c : compDao.loadVideoComps(assetUuid)) {
			response.add(toResponse(c));
		}
		for (AssetAudioComp c : compDao.loadAudioComps(assetUuid)) {
			response.add(toResponse(c));
		}
		for (AssetDocComp c : compDao.loadDocComps(assetUuid)) {
			response.add(toResponse(c));
		}
		for (AssetTranscriptComp c : compDao.loadTranscriptComps(assetUuid)) {
			response.add(toResponse(c));
		}
		for (AssetJsonComp c : compDao.loadJsonComps(assetUuid)) {
			response.add(toResponse(c));
		}
		return response;
	}
}
