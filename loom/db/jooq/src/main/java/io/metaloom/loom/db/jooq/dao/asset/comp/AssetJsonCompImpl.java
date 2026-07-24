package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.vertx.core.json.JsonObject;

public class AssetJsonCompImpl extends AbstractAssetCompImpl<AssetJsonComp> implements AssetJsonComp {

	private String schemaType;
	private String variant = "";
	private JsonObject data;

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
	public String getVariant() {
		return variant;
	}

	@Override
	public AssetJsonComp setVariant(String variant) {
		this.variant = variant == null ? "" : variant;
		return this;
	}

	@Override
	public JsonObject getData() {
		return data;
	}

	@Override
	public AssetJsonComp setData(JsonObject data) {
		this.data = data;
		return this;
	}
}
