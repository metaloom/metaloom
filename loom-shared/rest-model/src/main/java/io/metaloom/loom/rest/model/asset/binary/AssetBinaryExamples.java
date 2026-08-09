package io.metaloom.loom.rest.model.asset.binary;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AssetBinaryExamples extends ExampleValues {

	default Example binaryResponseExample() {
		return new ExampleImpl(binaryResponse(), "The location response", HttpResponseStatus.OK);
	}

	default Example binaryUpdateRequestExample() {
		return new ExampleImpl(binaryUpdateRequest(), "The location update request", HttpResponseStatus.OK);
	}

	default Example binaryCreateRequestExample() {
		return new ExampleImpl(binaryCreateRequest(), "The location create request", HttpResponseStatus.CREATED);
	}

	default Example binaryListResponseExample() {
		return new ExampleImpl(binaryListResponse(), "The location list response", HttpResponseStatus.OK);
	}
	
	default AssetBinaryResponse binaryResponse() {
		AssetBinaryResponse model = new AssetBinaryResponse();
		model.setFilesystem(new AssetBinaryFilesystemInfo().setFilekey(new FileKey(42L, 12L, 12L, 3L)).setLastSeen(DATE_NEW)
			.setPath("/the-current-path/bigbuckbunny-4k.mp4"));
		model.setS3(new AssetS3Meta().setBucket("big_bucket").setObjectPath("themovie"));
		return model;
	}

	default AssetBinaryListResponse binaryListResponse() {
		AssetBinaryListResponse model = new AssetBinaryListResponse();
		model.setMetainfo(pagingInfo());
		model.add(binaryResponse());
		model.add(binaryResponse());
		return model;
	}

	/**
	 * The update request as a relocation would send it: a new path, plus the library and pool the bytes now belong to.
	 *
	 * <p>
	 * Every field is set deliberately. The OpenAPI document is example-driven, so a field no example populates is documented nowhere.
	 * </p>
	 */
	default AssetBinaryUpdateRequest binaryUpdateRequest() {
		AssetBinaryUpdateRequest model = new AssetBinaryUpdateRequest();
		model.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/the-new-path/bigbuckbunny-4k.mp4"));
		model.setLibraryUuid(uuidB());
		model.setPoolUuid(uuidC());
		return model;
	}

	default AssetBinaryCreateRequest binaryCreateRequest() {
		AssetBinaryCreateRequest model = new AssetBinaryCreateRequest();
		model.setAssetUuid(uuidA());
		model.setLibraryUuid(uuidB());
		model.setPoolUuid(uuidC());
		model.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/the-current-path/bigbuckbunny-4k.mp4"));
		return model;
	}

	default AssetBinaryReference binaryReference() {
		AssetBinaryReference model = new AssetBinaryReference();
		model.setUuid(uuidC());
		model.setPath("/the-current-path/bigbuckbunny-4k.mp4");
		return model;
	}
}
