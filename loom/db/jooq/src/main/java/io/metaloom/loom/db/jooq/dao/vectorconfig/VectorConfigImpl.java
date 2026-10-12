package io.metaloom.loom.db.jooq.dao.vectorconfig;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.vectorconfig.VectorConfig;
import io.vertx.core.json.JsonObject;

public class VectorConfigImpl extends AbstractEditableElement<VectorConfig> implements VectorConfig {

	private String name;

	private JsonObject weights;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public VectorConfig setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public JsonObject getWeights() {
		return weights;
	}

	@Override
	public VectorConfig setWeights(JsonObject weights) {
		this.weights = weights;
		return this;
	}

}
