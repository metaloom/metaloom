package io.metaloom.loom.rest.model.asset;

import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationExamples;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AssetExamples extends ExampleValues, AssetLocationExamples {

	default Example assetResponseExample() {
		return new ExampleImpl(assetResponse(), "The asset response", HttpResponseStatus.OK);
	}

	default Example assetUpdateRequestExample() {
		return new ExampleImpl(assetUpdateRequest(), "The asset update request", HttpResponseStatus.OK);
	}

	default Example assetCreateRequestExample() {
		return new ExampleImpl(assetCreateRequest(), "The asset create request", HttpResponseStatus.CREATED);
	}

	default Example assetListResponseExample() {
		return new ExampleImpl(assetListResponse(), "The asset list response", HttpResponseStatus.OK);
	}

	default Example assetBulkCreateRequestExample() {
		return new ExampleImpl(assetBulkCreateRequest(), "The asset bulk create request", HttpResponseStatus.OK);
	}

	default Example assetBulkResponseExample() {
		return new ExampleImpl(assetBulkResponse(), "The asset bulk response", HttpResponseStatus.OK);
	}

	default Example assetBulkUpdateRequestExample() {
		return new ExampleImpl(assetBulkUpdateRequest(), "The asset bulk update request", HttpResponseStatus.OK);
	}

	
	default AssetResponse assetResponse() {
		AssetResponse model = new AssetResponse();
		setCreatorEditor(model);
		model.setUuid(uuidA());
		model.setFile(assetFileInfo());
		// model.getLicenses().add(new LicenseInfo().setName("license-name").setVersion("v1"));
		model.setMeta(meta());
		model.addLocation(locationResponse());
		model.setGeo(assetGeoLocation())
			.setAnnotations(assetAnnotations());

		model.getImageComponents().add(new ImageInfo().setDominantColor("#FFFF00"));
		model.getVideoComponents().add(new VideoInfo().setDuration(20000L).setWidth(4000).setHeight(2250));
		model.getAudioComponents().add(new AudioInfo().setBpm(120).setChannels(2).setSamplingRate(48000).setEncoding("mp3"));
		model.setMeta(meta());
		model.setHashes(assetHashes());

		model.getTags().add(tagReferenceA());
		// .setLocalPath("/opt/movies/bigbuckbunny-4k.mp4")
		// .setS3(new AssetS3Meta().setBucket("big_bucket").setKey("themovie"))
		// .setMimeType("video/mp4")
		return model;
	}

	default AssetReference assetReference() {
		AssetReference model = new AssetReference();
		model.setUuid(uuidA());
		model.setSha512sum(sha512sum());
		return model;
	}

	default AssetListResponse assetListResponse() {
		AssetListResponse model = new AssetListResponse();
		model.add(assetResponse());
		model.add(assetResponse());
		model.setMetainfo(pagingInfo());
		return model;
	}

	default AssetUpdateRequest assetUpdateRequest() {
		AssetUpdateRequest model = new AssetUpdateRequest();
		model.setImage(assetImageInfo())
			.setMeta(meta())
			.setFile(assetFileInfo())
			.setGeo(assetGeoLocation())
			.setTimeline(assetAnnotations());
		return model;
	}

	default ImageInfo assetImageInfo() {
		ImageInfo info = new ImageInfo();
		info.setDominantColor("#FFFF00");
		return info;
	}

	default AssetCreateRequest assetCreateRequest() {
		AssetCreateRequest model = new AssetCreateRequest();
		model.setImage(assetImageInfo())
			.setMeta(meta())
			.setGeo(assetGeoLocation())
			.setTimeline(assetAnnotations())
			.setFile(assetFileInfo());
		return model;
	}

	default ConsistencyInfo assetConsistencyInfo() {
		ConsistencyInfo info = new ConsistencyInfo();
		info.setZeroChunkCount(42L);
		return info;
	}

	default FileInfo assetFileInfo() {
		FileInfo info = new FileInfo();
		info.setSize(42L * 1000 * 1000);
		info.setFilename("bigbuckbunny-4k.mp4");
		info.setMimeType("video/mp4");
		info.setOrigin("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
		info.setFirstSeen(DATE_OLD);
		return info;
	}

	default AssetBulkCreateRequest assetBulkCreateRequest() {
		AssetBulkCreateRequest request = new AssetBulkCreateRequest();
		request.add(assetCreateRequest());
		request.add(assetCreateRequest());
		return request;
	}

	default AssetBulkResponse assetBulkResponse() {
		AssetBulkResponse response = new AssetBulkResponse();
		response.setTotal(2);
		response.setCreated(2);
		response.setFailed(0);
		AssetBulkItemResponse item = new AssetBulkItemResponse();
		item.setIndex(0);
		item.setUuid(uuidA());
		item.setStatus(AssetBulkItemResponse.BulkItemStatus.CREATED);
		response.add(item);
		return response;
	}

	default AssetBulkUpdateRequest assetBulkUpdateRequest() {
		AssetBulkUpdateRequest request = new AssetBulkUpdateRequest();
		AssetBulkUpdateEntry entry = new AssetBulkUpdateEntry();
		entry.setHashes(new HashInfo().setSHA512(sha512sum()));
		entry.setUpdate(assetUpdateRequest());
		request.add(entry);
		return request;
	}

}
