package io.metaloom.loom.rest.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * The origin a worker reports is persisted on the task row (V2.117), but only in the vocabulary the column's CHECK accepts - a worker from another
 * build must not be able to fail a whole batch flush with a value Loom does not know.
 */
public class DaoRunStateStoreOriginTest {

	private static NodeTaskResult result(String origin) {
		return NodeTaskResult.completed(null, "n", 10, Map.of()).withOrigin(origin);
	}

	@Test
	public void testKnownOriginsPassThrough() {
		assertEquals("COMPUTED", DaoRunStateStore.originOf(result("COMPUTED")));
		assertEquals("LOCAL", DaoRunStateStore.originOf(result("LOCAL")));
		assertEquals("REMOTE", DaoRunStateStore.originOf(result("REMOTE")));
	}

	@Test
	public void testUnknownOrMissingOriginIsNull() {
		assertNull(DaoRunStateStore.originOf(result(null)));
		assertNull(DaoRunStateStore.originOf(result("CACHED")));
		assertNull(DaoRunStateStore.originOf(result("remote")));
	}
}
