package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class ClusterCreateRequest implements RestRequestModel, ClusterModel<ClusterCreateRequest> {

	private String name;

	private String type;

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

	/**
	 * Return the kind of cluster, e.g. "person". Optional - the endpoint falls back to a generic default.
	 */
	public String getType() {
		return type;
	}

	public ClusterCreateRequest setType(String type) {
		this.type = type;
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
