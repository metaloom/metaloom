package io.metaloom.loom.rest.model;

import io.vertx.core.json.JsonObject;

public interface MetaModel<T extends MetaModel<T>> extends RestModel {

	JsonObject getMeta();

	T setMeta(JsonObject meta);

	T self();

}
