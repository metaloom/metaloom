package io.metaloom.loom.db.model.vectorconfig;

import io.metaloom.loom.db.CUDElement;
import io.vertx.core.json.JsonObject;

/**
 * A custom vector index definition (V2.6): which components of Loom data feed a vector, and how strongly each one is weighted.
 */
public interface VectorConfig extends CUDElement<VectorConfig> {

	/**
	 * Unique across the installation.
	 */
	String getName();

	VectorConfig setName(String name);

	/**
	 * The index definition: the weight of each component that is included in the vector.
	 */
	JsonObject getWeights();

	VectorConfig setWeights(JsonObject weights);

}
