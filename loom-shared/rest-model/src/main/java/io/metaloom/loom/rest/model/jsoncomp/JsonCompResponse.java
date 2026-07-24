package io.metaloom.loom.rest.model.jsoncomp;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.vertx.core.json.JsonObject;

public class JsonCompResponse extends AbstractCreatorEditorRestResponse<JsonCompResponse>
	implements JsonCompModel<JsonCompResponse> {

	private String assetUuid;
	private String nodeKind;
	private String schemaType;
	private String variant;
	private String producerVersion;
	private JsonObject data;

	public String getAssetUuid() {
		return assetUuid;
	}

	public JsonCompResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getNodeKind() {
		return nodeKind;
	}

	@Override
	public JsonCompResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	@Override
	public String getSchemaType() {
		return schemaType;
	}

	@Override
	public JsonCompResponse setSchemaType(String schemaType) {
		this.schemaType = schemaType;
		return this;
	}

	@Override
	public String getVariant() {
		return variant;
	}

	@Override
	public JsonCompResponse setVariant(String variant) {
		this.variant = variant;
		return this;
	}

	@Override
	public String getProducerVersion() {
		return producerVersion;
	}

	@Override
	public JsonCompResponse setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	@Override
	public JsonObject getData() {
		return data;
	}

	@Override
	public JsonCompResponse setData(JsonObject data) {
		this.data = data;
		return this;
	}

	@Override
	public JsonCompResponse self() {
		return this;
	}

}
