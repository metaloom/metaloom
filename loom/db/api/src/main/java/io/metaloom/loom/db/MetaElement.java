package io.metaloom.loom.db;

import io.vertx.core.json.JsonObject;

/**
 * Meta elements are elements which store additional meta properties (JSON).
 */
public interface MetaElement<SELF extends MetaElement<SELF>> extends Element<SELF> {

	/**
	 * Return the meta properties.
	 * 
	 * @return
	 */
	JsonObject getMeta();

	/**
	 * Set the meta properties.
	 * 
	 * @param meta
	 * @return Fluent API
	 */
	SELF setMeta(JsonObject meta);

}
