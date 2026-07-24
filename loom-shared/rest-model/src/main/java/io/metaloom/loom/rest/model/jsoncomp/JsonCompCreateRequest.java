package io.metaloom.loom.rest.model.jsoncomp;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;
import io.vertx.core.json.JsonObject;

/**
 * Create/upsert request for a generic JSON component. Keyed by {@code (asset, node_kind, schema_type, variant)}; re-posting the same key rewrites the
 * row.
 */
public class JsonCompCreateRequest extends AbstractMetaModel<JsonCompCreateRequest>
	implements RestRequestModel, JsonCompModel<JsonCompCreateRequest> {

	private String nodeKind;
	private String schemaType;
	private String variant;
	private String producerVersion;
	private JsonObject data;

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public JsonCompCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getSchemaType() {
		return schemaType;
	}

	@Override
	public JsonCompCreateRequest setSchemaType(String schemaType) {
		this.schemaType = schemaType;
		return this;
	}

	@Override
	public String getVariant() {
		return variant;
	}

	@Override
	public JsonCompCreateRequest setVariant(String variant) {
		this.variant = variant;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public JsonCompCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public JsonObject getData() {
		return data;
	}

	@Override
	public JsonCompCreateRequest setData(JsonObject data) {
		this.data = data;
		return this;
	}

	@Override
	public JsonCompCreateRequest self() {
		return this;
	}

}
