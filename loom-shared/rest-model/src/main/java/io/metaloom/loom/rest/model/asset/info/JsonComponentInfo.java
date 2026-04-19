package io.metaloom.loom.rest.model.asset.info;

import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public class JsonComponentInfo implements RestModel {

	private String schemaType;
	private JsonObject data;

	public String getSchemaType() {
		return schemaType;
	}

	public JsonComponentInfo setSchemaType(String schemaType) {
		this.schemaType = schemaType;
		return this;
	}

	public JsonObject getData() {
		return data;
	}

	public JsonComponentInfo setData(JsonObject data) {
		this.data = data;
		return this;
	}
}
