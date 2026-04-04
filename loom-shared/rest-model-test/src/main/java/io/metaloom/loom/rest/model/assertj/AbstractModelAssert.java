package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public abstract class AbstractModelAssert<U extends AbstractAssert<U, T>, T> extends AbstractAssert<U, T> {

	protected AbstractModelAssert(T actual, Class<?> selfType) {
		super(actual, selfType);
	}

	protected void assertJson(JsonObject modelA, JsonObject modelB, String msg) {
		if (modelA == modelB) {
			return;
		}
		if (modelA == null && modelB == null) {
			return;
		}
		assertEquals(modelA.encodePrettily(), modelB.encodePrettily(), msg);
	}

	protected void assertJson(RestModel modelA, RestModel modelB, String msg) {
		assertEquals(LoomJson.encode(modelA), LoomJson.encode(modelB), msg);
	}
}
