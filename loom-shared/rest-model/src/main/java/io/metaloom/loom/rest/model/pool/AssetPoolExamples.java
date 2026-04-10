package io.metaloom.loom.rest.model.pool;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AssetPoolExamples extends ExampleValues {

	default Example poolCreateRequestExample() {
		return new ExampleImpl(poolCreateRequest(), "The asset pool create request", HttpResponseStatus.CREATED);
	}

	default Example poolUpdateRequestExample() {
		return new ExampleImpl(poolUpdateRequest(), "The asset pool update request", HttpResponseStatus.OK);
	}

	default Example poolResponseExample() {
		return new ExampleImpl(poolResponse(), "The asset pool response", HttpResponseStatus.OK);
	}

	default Example poolListResponseExample() {
		return new ExampleImpl(poolListResponse(), "The asset pool list response", HttpResponseStatus.OK);
	}

	default AssetPoolResponse poolResponse() {
		AssetPoolResponse model = new AssetPoolResponse();
		model.setUuid(uuidC());
		model.setName("primary-storage");
		model.setFsPath("/tank/loom/binaries");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default AssetPoolListResponse poolListResponse() {
		AssetPoolListResponse model = new AssetPoolListResponse();
		model.setMetainfo(pagingInfo());
		model.add(poolResponse());
		model.add(poolResponse());
		return model;
	}

	default AssetPoolCreateRequest poolCreateRequest() {
		AssetPoolCreateRequest model = new AssetPoolCreateRequest();
		model.setName("primary-storage");
		model.setFsPath("/tank/loom/binaries");
		return model;
	}

	default AssetPoolUpdateRequest poolUpdateRequest() {
		AssetPoolUpdateRequest model = new AssetPoolUpdateRequest();
		model.setName("updated-storage");
		return model;
	}

}
