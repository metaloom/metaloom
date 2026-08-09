package io.metaloom.loom.rest.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.asset.location.license.LicenseInfo;
import io.metaloom.loom.rest.model.asset.location.social.SocialInfo;
import io.metaloom.loom.rest.model.tag.TagReference;

public interface AssetModelBuilder extends ModelBuilder, UserModelBuilder, AssetLocationModelBuilder, TagModelBuilder, AnnotationModelBuilder {

	default List<LicenseInfo> assetLicense(Asset asset) {
		List<LicenseInfo> info = new ArrayList<>();
		return info;
	}

	default List<TagReference> assetTags(Asset asset) {
		List<AssetTag> tags = daos().tagDao().assetTags(asset);
		return tags.stream().map(tag -> toReference(tag)).collect(Collectors.toList());
	}

	default AssetResponse toResponse(Asset asset) {
		AssetResponse response = new AssetResponse();
		UUID assetUuid = asset.getUuid();

		response.setUuid(assetUuid);
		response.setMeta(asset.getMeta());
		response.setFile(assetFileInfo(asset));
		response.setConsistency(assetConsistencyInfo(asset));
		response.setHashes(assetHasheInfo(asset));
		response.setSocial(assetSocialInfo(asset));
		response.setMeta(asset.getMeta());
		setStatus(asset, response);

		// Load and set component lists from dedicated component tables
		AssetComponentDao compDao = daos().assetComponentDao();

		// Geo components
		List<GeoLocationInfo> geoList = compDao.loadGeoComps(assetUuid).stream()
			.map(this::toGeoInfo).collect(Collectors.toList());
		response.setGeoComponents(geoList);
		if (!geoList.isEmpty()) {
			response.setGeo(geoList.get(0));
		}

		// Image components
		List<ImageInfo> imageList = compDao.loadImageComps(assetUuid).stream()
			.map(this::toImageInfo).collect(Collectors.toList());
		response.setImageComponents(imageList);

		// Video components
		List<VideoInfo> videoList = compDao.loadVideoComps(assetUuid).stream()
			.map(this::toVideoInfo).collect(Collectors.toList());
		response.setVideoComponents(videoList);

		// Audio components
		List<AudioInfo> audioList = compDao.loadAudioComps(assetUuid).stream()
			.map(this::toAudioInfo).collect(Collectors.toList());
		response.setAudioComponents(audioList);

		// Document components
		List<DocumentInfo> docList = compDao.loadDocComps(assetUuid).stream()
			.map(this::toDocumentInfo).collect(Collectors.toList());
		response.setDocumentComponents(docList);

		List<AssetBinary> locations = daos().assetBinaryDao().loadAllByAssetUuid(assetUuid);
		List<AssetLocationResponse> locationModels = locations.stream().map(this::toLocationResponse).collect(Collectors.toList());
		response.setLocations(locationModels);

		response.setTags(assetTags(asset));
		List<Annotation> annotations = daos().annotationDao().loadForAsset(assetUuid);
		List<AnnotationResponse> restAnnotations = annotations.stream().map(this::toResponse).collect(Collectors.toList());
		response.setAnnotations(restAnnotations);

		return validator().validate(response);
	}

	default GeoLocationInfo toGeoInfo(AssetGeoComp comp) {
		GeoLocationInfo info = new GeoLocationInfo();
		info.setSource(comp.getNodeKind());
		info.setLat(comp.getGeoLat());
		info.setLon(comp.getGeoLon());
		info.setAlias(comp.getGeoAlias());
		return info;
	}

	default ImageInfo toImageInfo(AssetImageComp comp) {
		ImageInfo info = new ImageInfo();
		info.setSource(comp.getNodeKind());
		info.setDominantColor(comp.getImageDominantColor());
		info.setWidth(comp.getMediaWidth());
		info.setHeight(comp.getMediaHeight());
		return info;
	}

	default VideoInfo toVideoInfo(AssetVideoComp comp) {
		VideoInfo info = new VideoInfo();
		info.setSource(comp.getNodeKind());
		info.setBitrate(comp.getVideoBitrate());
		info.setEncoding(comp.getVideoEncoding());
		info.setWidth(comp.getMediaWidth());
		info.setHeight(comp.getMediaHeight());
		info.setDuration(comp.getMediaDuration());
		return info;
	}

	default AudioInfo toAudioInfo(AssetAudioComp comp) {
		AudioInfo info = new AudioInfo();
		info.setSource(comp.getNodeKind());
		info.setBpm(comp.getAudioBpm());
		info.setBitrate(comp.getAudioBitrate());
		info.setChannels(comp.getAudioChannels());
		info.setEncoding(comp.getAudioEncoding());
		info.setSamplingRate(comp.getAudioSamplingRate());
		info.setDuration(comp.getMediaDuration());
		return info;
	}

	default DocumentInfo toDocumentInfo(AssetDocComp comp) {
		DocumentInfo info = new DocumentInfo();
		info.setSource(comp.getNodeKind());
		info.setWordCount(comp.getDocWordCount() != null ? comp.getDocWordCount().longValue() : null);
		info.setPlainText(comp.getDocPlainText());
		return info;
	}

	default ConsistencyInfo assetConsistencyInfo(Asset asset) {
		ConsistencyInfo info = new ConsistencyInfo();
		info.setZeroChunkCount(asset.getZeroChunkCount());
		return info;
	}

	default SocialInfo assetSocialInfo(Asset asset) {
		SocialInfo social = new SocialInfo();
		return social;
	}

	default HashInfo assetHasheInfo(Asset asset) {
		HashInfo hashes = new HashInfo();
		hashes.setSHA512(asset.getSHA512());
		hashes.setSHA256(asset.getSHA256());
		hashes.setMD5(asset.getMD5());
		hashes.setChunkHash(asset.getChunkHash());
		return hashes;
	}

	default FileInfo assetFileInfo(Asset asset) {
		FileInfo info = new FileInfo();
		info.setMimeType(asset.getMimeType());
		info.setSize(asset.getSize());
		info.setFilename(asset.getFilename());
		info.setOrigin(asset.getInitialOrigin());
		info.setFirstSeen(asset.getFirstSeen());
		return info;
	}

	default AssetListResponse toAssetList(Page<Asset> page) {
		return setPage(new AssetListResponse(), page, this::toResponse);
	}
}
