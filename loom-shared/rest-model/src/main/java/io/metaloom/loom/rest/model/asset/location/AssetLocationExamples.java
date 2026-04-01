package io.metaloom.loom.rest.model.asset.location;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AssetLocationExamples extends ExampleValues {

	default Example locationResponseExample() {
		return new ExampleImpl(locationResponse(), "The location response", HttpResponseStatus.OK);
	}

	default Example locationUpdateRequestExample() {
		return new ExampleImpl(locationUpdateRequest(), "The location update request", HttpResponseStatus.OK);
	}

	default Example locationCreateRequestExample() {
		return new ExampleImpl(locationCreateRequest(), "The location create request", HttpResponseStatus.CREATED);
	}

	default Example locationListResponseExample() {
		return new ExampleImpl(locationListResponse(), "The location list response", HttpResponseStatus.OK);
	}
	
	default AssetLocationResponse locationResponse() {
		AssetLocationResponse model = new AssetLocationResponse();
		model.setFilesystem(new AssetLocationFilesystemInfo().setFilekey(new FileKey(42L, 12L, 12L, 3L)).setLastSeen(DATE_NEW)
			.setPath("/the-current-path/bigbuckbunny-4k.mp4"));
		model.setS3(new AssetS3Meta().setBucket("big_bucket").setObjectPath("themovie"));
		return model;
	}

	default AssetLocationListResponse locationListResponse() {
		AssetLocationListResponse model = new AssetLocationListResponse();
		model.setMetainfo(pagingInfo());
		model.add(locationResponse());
		model.add(locationResponse());
		return model;
	}

	default AssetLocationUpdateRequest locationUpdateRequest() {
		AssetLocationUpdateRequest model = new AssetLocationUpdateRequest();

		return model;
	}
	
	default AssetLocationCreateRequest locationCreateRequest() {
		AssetLocationCreateRequest model = new AssetLocationCreateRequest();

		return model;
	}

	default AssetLocationReference locationReference() {
		AssetLocationReference model = new AssetLocationReference();
		model.setUuid(uuidC());
		model.setPath("/the-current-path/bigbuckbunny-4k.mp4");
		return model;
	}
}
