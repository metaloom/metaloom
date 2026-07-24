package io.metaloom.loom.db.model.asset;

import io.vertx.core.json.JsonObject;

/**
 * Generic JSON component of an asset - the node-agnostic sink for results with no query requirement.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, schema_type, variant)</code>. An LLM node configured with three prompts writes three components, one per
 * prompt id.
 * </p>
 *
 * <p>
 * Promotion policy: a node kind starts here and graduates to a typed component table when a query must filter on a field inside the payload, when the
 * UI renders it as a first-class object, or when it needs a foreign key.
 * </p>
 */
public interface AssetJsonComp extends AssetComponent<AssetJsonComp> {

	/**
	 * Return the shape label for the payload, e.g. yolo-detection, caption, llm-answer.
	 */
	String getSchemaType();

	AssetJsonComp setSchemaType(String schemaType);

	/**
	 * Return the sub-division within the node kind: prompt id, model tag, whatever makes two results of the same node distinct. Never null - the
	 * default is the empty string.
	 */
	String getVariant();

	AssetJsonComp setVariant(String variant);

	JsonObject getData();

	AssetJsonComp setData(JsonObject data);
}
