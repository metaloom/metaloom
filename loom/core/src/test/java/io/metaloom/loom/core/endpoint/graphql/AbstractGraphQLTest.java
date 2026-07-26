package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;
import io.vertx.core.json.JsonObject;

/**
 * Shared base for the per domain GraphQL query tests.
 *
 * <p>Each concrete subclass targets a single domain element (e.g. {@code UserGraphQLTest}, {@code RoleGraphQLTest}) and exercises the read side of the
 * GraphQL API for that element against a live server plus a real (pooled) database. This base only provides the plumbing that every one of those tests
 * shares: sending a query, asserting the top level {@code errors} array and reaching into the {@code data} tree.</p>
 *
 * <p>The GraphQL response {@code data} is a {@link JsonObject} whose {@link JsonObject#getMap()} yields nested {@link Map}s and {@link List}s, which is why
 * the accessors below hand back {@code Map<String, Object>} / {@code List<...>} rather than typed models.</p>
 */
public abstract class AbstractGraphQLTest extends AbstractEndpointTest implements GraphQLSecurityTestcases {

	// daos() and adminUuid() are inherited from AbstractEndpointTest.

	// ── Query execution ──────────────────────────────────────────────────

	/**
	 * Execute a query without variables.
	 */
	protected GraphQLResponse query(LoomHttpClient client, String query) throws LoomClientException {
		return client.executeGraphQL(new GraphQLRequest(query)).sync().body();
	}

	/**
	 * Execute a query with the given variables.
	 */
	protected GraphQLResponse query(LoomHttpClient client, String query, JsonObject variables) throws LoomClientException {
		return client.executeGraphQL(new GraphQLRequest(query, variables)).sync().body();
	}

	/**
	 * Execute a query and return the {@code data} map, asserting that no errors were reported.
	 */
	protected Map<String, Object> data(LoomHttpClient client, String query) throws LoomClientException {
		return dataOf(assertNoErrors(query(client, query)));
	}

	/**
	 * Execute a query with variables and return the {@code data} map, asserting that no errors were reported.
	 */
	protected Map<String, Object> data(LoomHttpClient client, String query, JsonObject variables) throws LoomClientException {
		return dataOf(assertNoErrors(query(client, query, variables)));
	}

	// ── Response assertions ──────────────────────────────────────────────

	/**
	 * Assert the response carries a non-null {@code data} object and an empty (or absent) {@code errors} array.
	 */
	protected GraphQLResponse assertNoErrors(GraphQLResponse response) {
		assertNotNull(response, "Expected a GraphQL response");
		assertTrue(response.getErrors() == null || response.getErrors().isEmpty(),
			"Expected no errors but got: " + response.getErrors());
		assertNotNull(response.getData(), "Expected a data object in the response");
		return response;
	}

	/**
	 * Assert the response carries at least one error. Use this for plain GraphQL validation failures (e.g. an unknown field), which do not carry one of
	 * our custom {@code code} extensions.
	 */
	protected void assertHasErrors(GraphQLResponse response) {
		assertNotNull(response.getErrors(), "Expected errors but got none");
		assertFalse(response.getErrors().isEmpty(), "Expected at least one error");
	}

	/**
	 * Assert the response carries at least one error whose {@code extensions.code} equals the expected code (e.g. {@code FORBIDDEN},
	 * {@code UNAUTHENTICATED}, {@code BAD_USER_INPUT}).
	 */
	protected void assertErrorCode(GraphQLResponse response, String expectedCode) {
		assertNotNull(response.getErrors(), "Expected errors but got none");
		assertFalse(response.getErrors().isEmpty(), "Expected at least one error");
		JsonObject first = response.getErrors().getJsonObject(0);
		JsonObject extensions = first.getJsonObject("extensions");
		assertNotNull(extensions, "Expected the error to carry an extensions object: " + first);
		assertEquals(expectedCode, extensions.getString("code"), "Unexpected error code in: " + first);
	}

	// ── Permission-check helpers (see GraphQLSecurityTestcases) ──────────

	// loginPermissionlessClient() is inherited from AbstractEndpointTest.

	/**
	 * Assert the response was denied with a {@code FORBIDDEN} error that names the given missing permission.
	 */
	protected void assertForbidden(GraphQLResponse response, Permission permission) {
		assertErrorCode(response, "FORBIDDEN");
		JsonObject extensions = response.getErrors().getJsonObject(0).getJsonObject("extensions");
		assertEquals(permission.name(), extensions.getString("permission"),
			"The FORBIDDEN error should name the missing permission");
	}

	/**
	 * Execute a retrieval query (no variables) and assert it is denied because the caller lacks {@code permission}.
	 */
	protected void assertRetrievalForbidden(LoomHttpClient client, Permission permission, String query) throws LoomClientException {
		assertForbidden(query(client, query), permission);
	}

	/**
	 * Execute a retrieval query with variables and assert it is denied because the caller lacks {@code permission}.
	 */
	protected void assertRetrievalForbidden(LoomHttpClient client, Permission permission, String query, JsonObject variables) throws LoomClientException {
		assertForbidden(query(client, query, variables), permission);
	}

	// ── Data navigation ──────────────────────────────────────────────────

	protected Map<String, Object> dataOf(GraphQLResponse response) {
		return response.getData().getMap();
	}

	@SuppressWarnings("unchecked")
	protected Map<String, Object> object(Map<String, Object> parent, String field) {
		return (Map<String, Object>) parent.get(field);
	}

	@SuppressWarnings("unchecked")
	protected List<Map<String, Object>> list(Map<String, Object> parent, String field) {
		return (List<Map<String, Object>>) parent.get(field);
	}
}
