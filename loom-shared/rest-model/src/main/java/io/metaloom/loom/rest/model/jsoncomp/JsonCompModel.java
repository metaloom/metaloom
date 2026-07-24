package io.metaloom.loom.rest.model.jsoncomp;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

/**
 * Wire model for a generic JSON component ({@code asset_json_comp}) - the node-agnostic sink for node output that has no dedicated query requirement.
 *
 * <p>
 * Identity: {@code (asset_uuid, node_kind, schema_type, variant)}. A cortex node posts its structured result here as an opaque {@code data} payload;
 * re-posting the same key upserts the row. This is the lightweight, customer-facing way for a custom node to persist results without a dedicated typed
 * component table.
 * </p>
 */
public interface JsonCompModel<T extends JsonCompModel<T>> extends MetaModel<T>, RestModel {

	/**
	 * Return the kind of node that produced the result, e.g. {@code hello-world}. Part of the component identity.
	 */
	String getNodeKind();

	T setNodeKind(String nodeKind);

	/**
	 * Return the shape label for the payload, e.g. {@code yolo-detection}, {@code caption}, {@code hello-world}. Never null on a stored row - it is part
	 * of the component identity.
	 */
	String getSchemaType();

	T setSchemaType(String schemaType);

	/**
	 * Return the sub-division within the node kind: prompt id, model tag, whatever makes two results of the same node distinct. Never null - the default
	 * is the empty string.
	 */
	String getVariant();

	T setVariant(String variant);

	/**
	 * Return the model or algorithm version of the producer, or null.
	 */
	String getProducerVersion();

	T setProducerVersion(String producerVersion);

	/**
	 * Return the opaque JSON payload the node produced.
	 */
	JsonObject getData();

	T setData(JsonObject data);

}
