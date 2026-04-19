package io.metaloom.loom.db.model.asset;

import io.vertx.core.json.JsonObject;

/**
 * Generic JSON component of an asset. Can be used to store arbitrary structured data
 * produced by Cortex processing nodes or other external sources.
 */
public interface AssetJsonComp extends AssetComponent<AssetJsonComp> {

	String getSchemaType();

	AssetJsonComp setSchemaType(String schemaType);

	JsonObject getData();

	AssetJsonComp setData(JsonObject data);
}
