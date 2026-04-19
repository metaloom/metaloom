package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.vertx.core.json.JsonObject;

public class AssetJsonCompImpl extends AbstractEditableElement<AssetJsonComp> implements AssetJsonComp {

	private UUID assetUuid;
	private String source;
	private String schemaType;
	private String data;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetJsonComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetJsonComp setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getSchemaType() {
		return schemaType;
	}

	@Override
	public AssetJsonComp setSchemaType(String schemaType) {
		this.schemaType = schemaType;
		return this;
	}

	@Override
	public JsonObject getData() {
		return data != null ? new JsonObject(data) : null;
	}

	@Override
	public AssetJsonComp setData(JsonObject data) {
		this.data = data != null ? data.encode() : null;
		return this;
	}
}
