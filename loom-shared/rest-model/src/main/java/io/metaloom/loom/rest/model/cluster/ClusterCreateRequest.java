package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class ClusterCreateRequest implements RestRequestModel, ClusterModel<ClusterCreateRequest> {

	private String name;

	private JsonObject meta;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ClusterCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public ClusterCreateRequest setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public ClusterCreateRequest self() {
		return this;
	}
}
