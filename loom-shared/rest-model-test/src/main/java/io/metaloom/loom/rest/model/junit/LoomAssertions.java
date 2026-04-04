package io.metaloom.loom.rest.model.junit;

import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.json.JsonObject;

public final class LoomAssertions {

	private LoomAssertions() {
	}

	public static void assertEqualsJson(JsonObject expected, JsonObject actual, String msg) {
		if (expected != actual) {
			fail("Objects did not match: " + msg);
		}
		if (expected != null && actual != null) {
			assertEquals(expected.encodePrettily(), actual.encodePrettily(), msg);
		}
	}
}
