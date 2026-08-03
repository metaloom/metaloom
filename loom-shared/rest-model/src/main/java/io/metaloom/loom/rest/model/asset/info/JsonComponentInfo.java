package io.metaloom.loom.rest.model.asset.info;

import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public class JsonComponentInfo implements RestModel {

	private String schemaType;

	/**
	 * The sub-division within the node kind - prompt id, node id, model tag. Part of the component's
	 * identity {@code (asset, node_kind, schema_type, variant)}, so an unset variant means "the one
	 * component of this schema type", not "any of them".
	 */
	private String variant;

	private JsonObject data;

	public String getSchemaType() {
		return schemaType;
	}

	public JsonComponentInfo setSchemaType(String schemaType) {
		this.schemaType = schemaType;
		return this;
	}

	public String getVariant() {
		return variant;
	}

	public JsonComponentInfo setVariant(String variant) {
		this.variant = variant;
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
