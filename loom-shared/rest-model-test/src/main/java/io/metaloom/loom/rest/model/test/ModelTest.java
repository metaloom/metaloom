package io.metaloom.loom.rest.model.test;

import io.vertx.core.json.JsonObject;

public interface ModelTest {

	default JsonObject meta() {
		JsonObject json = new JsonObject();
		json.put("hello", "world");
		return json;
	}

}
