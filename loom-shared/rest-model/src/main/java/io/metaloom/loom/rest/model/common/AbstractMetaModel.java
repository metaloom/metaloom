package io.metaloom.loom.rest.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.MetaModel;
import io.vertx.core.json.JsonObject;

public abstract class AbstractMetaModel<T extends MetaModel<T>> implements MetaModel<T> {

	@JsonProperty(required = false)
	@JsonPropertyDescription("Additional custom meta properties for the element.")
	private JsonObject meta;

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public T setMeta(JsonObject meta) {
		this.meta = meta;
		return self();
	}
}
