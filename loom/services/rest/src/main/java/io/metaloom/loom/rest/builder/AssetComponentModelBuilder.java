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
		response.setMethod(comp.getMethod());
		response.setTimeFrom(comp.getTimeFrom());
		response.setGeo(new GeoLocationInfo()
			.setSource(comp.getNodeKind())
			.setLon(comp.getGeoLon())
			.setLat(comp.getGeoLat())
			.setAlias(comp.getGeoAlias())
			.setAccuracyM(comp.getAccuracyM()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetImageComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.IMAGE);
		response.setStreamIndex(comp.getStreamIndex());
		response.setImage(new ImageInfo()
			.setSource(comp.getNodeKind())
			.setDominantColor(comp.getImageDominantColor())
			.setWidth(comp.getMediaWidth())
			.setHeight(comp.getMediaHeight())
			.setOrientation(comp.getOrientation())
			.setBitDepth(comp.getBitDepth())
			.setEncoding(comp.getImageEncoding()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetVideoComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.VIDEO);
		response.setStreamIndex(comp.getStreamIndex());
		response.setVideo(new VideoInfo()
			.setSource(comp.getNodeKind())
			.setBitrate(comp.getVideoBitrate())
			.setEncoding(comp.getVideoEncoding())
			.setWidth(comp.getMediaWidth())
			.setHeight(comp.getMediaHeight())
			.setDuration(comp.getMediaDuration())
			.setFps(comp.getFps())
			.setFrameCount(comp.getFrameCount())
			.setRotation(comp.getRotation()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetAudioComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.AUDIO);
		response.setStreamIndex(comp.getStreamIndex());
		response.setAudio(new AudioInfo()
			.setSource(comp.getNodeKind())
			.setBpm(comp.getAudioBpm())
			.setBitrate(comp.getAudioBitrate())
			.setChannels(comp.getAudioChannels())
			.setEncoding(comp.getAudioEncoding())
			.setSamplingRate(comp.getAudioSamplingRate())
			.setDuration(comp.getMediaDuration())
			.setLang(comp.getLang())
			.setTrackTitle(comp.getTrackTitle())
			.setIsDefault(comp.getIsDefault()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetDocComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.DOC);
		response.setPageNumber(comp.getPageNumber());
		response.setDocument(new DocumentInfo()
			.setSource(comp.getNodeKind())
			.setWordCount(comp.getDocWordCount() != null ? comp.getDocWordCount().longValue() : null)
			.setPlainText(comp.getDocPlainText())
			.setPageCount(comp.getPageCount())
			.setTextLang(comp.getTextLang()));
		return response;
	}

	default AssetComponentResponse toResponse(AssetTranscriptComp comp) {
		AssetComponentResponse response = new AssetComponentResponse();
		setCommon(response, comp, AssetComponentType.TRANSCRIPT);
		response.setStreamIndex(comp.getStreamIndex());
		response.setTranscript(new TranscriptInfo()
			.setSource(comp.getNodeKind())
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
			.setVariant(comp.getVariant())
			.setData(comp.getData()));
		return response;
	}

	default void setCommon(AssetComponentResponse response, AssetComponent<?> comp, AssetComponentType type) {
		response.setUuid(comp.getUuid());
		response.setType(type);
		response.setAssetUuid(comp.getAssetUuid());
		response.setSource(comp.getNodeKind());
		response.setNodeId(comp.getNodeId());
		response.setProducerVersion(comp.getProducerVersion());
		response.setConfidence(comp.getConfidence());
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
