package io.metaloom.loom.rest.endpoint.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.info.RESTInfoResponse;

/**
 * The info endpoint surfaces the DB schema revision + last-used timestamp from the
 * {@code loom} singleton row alongside the running server version. The DB row is optional
 * (the table is created without a seeded row), so a missing row must degrade to null
 * revision/last-used while still reporting the version.
 */
public class RESTInfoEndpointTest {

	@Test
	void testBuildResponseFromLoomRow() {
		LocalDateTime lastUsed = LocalDateTime.of(2026, 7, 20, 12, 34, 56);
		RESTInfoResponse response = RESTInfoEndpoint.buildResponse("1_0", "V2.5", lastUsed);

		assertEquals("1_0", response.getVersion());
		assertEquals("V2.5", response.getDbRevision());
		assertEquals(lastUsed.toString(), response.getLastUsed(), "The last-used timestamp must be serialised as ISO-8601");
	}

	@Test
	void testBuildResponseWithoutLoomRow() {
		RESTInfoResponse response = RESTInfoEndpoint.buildResponse("1_0", null, null);

		assertEquals("1_0", response.getVersion(), "The version must be reported even when the loom row is absent");
		assertNull(response.getDbRevision());
		assertNull(response.getLastUsed());
	}
}
