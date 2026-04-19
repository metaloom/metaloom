package io.metaloom.loom.rest.model.asset;

import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonObject;

public interface AssetComponentExamples extends ExampleValues {

	default Example assetComponentCreateRequestExample() {
		return new ExampleImpl(assetComponentCreateRequest(), "The asset component create request", HttpResponseStatus.CREATED);
	}

	default Example assetComponentUpdateRequestExample() {
		return new ExampleImpl(assetComponentUpdateRequest(), "The asset component update request", HttpResponseStatus.OK);
	}

	default Example assetComponentResponseExample() {
		return new ExampleImpl(assetComponentResponse(), "The asset component response", HttpResponseStatus.OK);
	}

	default Example assetComponentListResponseExample() {
		return new ExampleImpl(assetComponentListResponse(), "The asset component list response", HttpResponseStatus.OK);
	}

	default AssetComponentCreateRequest assetComponentCreateRequest() {
		AssetComponentCreateRequest request = new AssetComponentCreateRequest();
		request.setType(AssetComponentType.IMAGE);
		request.setSource("exif");
		request.setImage(new ImageInfo().setDominantColor("#FF0000").setWidth(1920).setHeight(1080));
		return request;
	}

	default AssetComponentUpdateRequest assetComponentUpdateRequest() {
		AssetComponentUpdateRequest request = new AssetComponentUpdateRequest();
		request.setType(AssetComponentType.IMAGE);
		request.setSource("exif-updated");
		request.setImage(new ImageInfo().setDominantColor("#00FF00").setWidth(3840).setHeight(2160));
		return request;
	}

	default AssetComponentResponse assetComponentResponse() {
		AssetComponentResponse response = new AssetComponentResponse();
		response.setUuid(uuidA());
		response.setType(AssetComponentType.IMAGE);
		response.setSource("exif");
		response.setImage(new ImageInfo().setDominantColor("#FF0000").setWidth(1920).setHeight(1080));
		return response;
	}

	default AssetComponentListResponse assetComponentListResponse() {
		AssetComponentListResponse list = new AssetComponentListResponse();
		list.add(assetComponentResponse());

		AssetComponentResponse geoComp = new AssetComponentResponse();
		geoComp.setUuid(uuidA());
		geoComp.setType(AssetComponentType.GEO);
		geoComp.setSource("exif");
		geoComp.setGeo(new GeoLocationInfo().setLon(2.3522).setLat(48.8566).setAlias("Paris"));
		list.add(geoComp);

		AssetComponentResponse jsonComp = new AssetComponentResponse();
		jsonComp.setUuid(uuidA());
		jsonComp.setType(AssetComponentType.JSON);
		jsonComp.setSource("yolo-detector");
		jsonComp.setJson(new JsonComponentInfo()
			.setSchemaType("yolo-detection")
			.setData(new JsonObject().put("detections", 3)));
		list.add(jsonComp);

		return list;
	}
}
