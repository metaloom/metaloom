package io.metaloom.loom.rest.model.blacklist;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface BlacklistExamples extends ExampleValues {

	default Example blacklistUpdateRequestExample() {
		return new ExampleImpl(blacklistUpdateRequest(), "The blacklist update request", HttpResponseStatus.OK);
	}

	default Example blacklistCreateRequestExample() {
		return new ExampleImpl(blacklistCreateRequest(), "The blacklist create request", HttpResponseStatus.CREATED);
	}

	default Example blacklistResponseExample() {
		return new ExampleImpl(blacklistResponse(), "The blacklist response", HttpResponseStatus.OK);
	}

	default Example blacklistListResponseExample() {
		return new ExampleImpl(blacklistListResponse(), "The blacklist list response", HttpResponseStatus.OK);
	}

	default BlacklistResponse blacklistResponse() {
		BlacklistResponse model = new BlacklistResponse();
		model.setUuid(uuidC());
		model.setName("Blocked asset");
		model.setAssetUuid(uuidA().toString());
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default BlacklistListResponse blacklistListResponse() {
		BlacklistListResponse model = new BlacklistListResponse();
		model.setMetainfo(pagingInfo());
		model.add(blacklistResponse());
		model.add(blacklistResponse());
		return model;
	}

	default BlacklistUpdateRequest blacklistUpdateRequest() {
		BlacklistUpdateRequest model = new BlacklistUpdateRequest();
		model.setName("Updated blacklist entry");
		model.setMeta(meta());
		return model;
	}

	default BlacklistCreateRequest blacklistCreateRequest() {
		BlacklistCreateRequest model = new BlacklistCreateRequest();
		model.setName("New blacklist entry");
		model.setAssetUuid(uuidA().toString());
		model.setMeta(meta());
		return model;
	}

}
