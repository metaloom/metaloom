package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the cross-entity {@code search} field.
 *
 * <p>The odd one out among the domain tests: {@code search} is one field backed by the {@code SearchProvider} SPI rather than a DAO, and it is the only
 * field of this schema whose permission check <em>narrows</em> the answer instead of rejecting the request outright. The narrowing and degradation cases
 * therefore carry the weight here; the ranking itself is covered by {@code SearchEndpointTest} and the provider tests, which exercise the same one code
 * path.</p>
 */
public class SearchGraphQLTest extends AbstractGraphQLTest {

	private static final String SEARCH = "query($q: String!) { search(q: $q) { totalHits totalExact warnings "
		+ "hits { entityType entityUuid assetUuid title score } } }";

	// --- fixtures ---------------------------------------------------------------------------------

	private Asset seedAsset(String filename) {
		Asset asset = daos().assetDao().createAsset(adminUuid(), SHA512.fromString(randomSha512()),
			"video/mp4", filename, "/media/" + filename, 2048L);
		daos().assetDao().store(asset);
		return asset;
	}

	private Tag seedTag(String name) {
		Tag tag = daos().tagDao().createTag(adminUuid(), name, "nature");
		daos().tagDao().store(tag);
		return tag;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	private JsonObject q(String term) {
		return new JsonObject().put("q", term);
	}

	private Map<String, Object> search(LoomHttpClient client, String term) throws LoomClientException {
		return object(data(client, SEARCH, q(term)), "search");
	}

	private boolean containsHit(Map<String, Object> search, UUID uuid) {
		return list(search, "hits").stream().anyMatch(hit -> uuid.toString().equals(hit.get("entityUuid")));
	}

	// --- happy path -------------------------------------------------------------------------------

	@Test
	public void testSearchFindsASeededAsset() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = seedAsset("gqlsearch_kittiwake.mp4");

			Map<String, Object> search = search(client, "gqlsearch_kittiwake");

			assertTrue(((Number) search.get("totalHits")).longValue() >= 1, "The seeded asset must be counted");
			assertEquals(true, search.get("totalExact"));
			Map<String, Object> hit = list(search, "hits").stream()
				.filter(h -> asset.getUuid().toString().equals(h.get("entityUuid")))
				.findFirst().orElse(null);
			assertNotNull(hit, "The seeded asset must be among the hits");
			assertEquals("ASSET", hit.get("entityType"));
			// An ASSET hit points at itself, which is what lets a client navigate from any hit to its asset.
			assertEquals(asset.getUuid().toString(), hit.get("assetUuid"));
			assertTrue(((Number) hit.get("score")).doubleValue() > 0, "A hit must carry its ranking score");
		}
	}

	/**
	 * Highlighting is driven by the selection set rather than by an argument, because it re-parses the whole source document per returned hit.
	 */
	@Test
	public void testHighlightsAreReturnedWhenSelected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seedAsset("gqlsearch_highlightable.mp4");

			Map<String, Object> withHighlights = object(data(client,
				"query($q: String!) { search(q: $q) { hits { title highlights } } }", q("gqlsearch_highlightable")), "search");
			Map<String, Object> firstHit = list(withHighlights, "hits").get(0);
			assertFalse(((List<?>) firstHit.get("highlights")).isEmpty(), "Selecting highlights must return a snippet");

			Map<String, Object> withoutHighlights = search(client, "gqlsearch_highlightable");
			assertFalse(list(withoutHighlights, "hits").isEmpty(), "The same query without highlights must still find the asset");
		}
	}

	@Test
	public void testTypeArgumentRestrictsTheResult() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Asset asset = seedAsset("gqlsearch_fulmar.mp4");
			Tag tag = seedTag("gqlsearch_fulmar");

			Map<String, Object> search = object(data(client,
				"query($q: String!) { search(q: $q, types: [TAG]) { hits { entityType entityUuid } } }", q("gqlsearch_fulmar")), "search");

			assertTrue(containsHit(search, tag.getUuid()), "The tag matches and was requested");
			assertFalse(containsHit(search, asset.getUuid()), "types: [TAG] must exclude the asset matching the same term");
		}
	}

	// --- permissions ------------------------------------------------------------------------------

	/**
	 * The permission semantic that is specific to search: types the caller may not read are dropped rather than the request being rejected, and the drop
	 * is reported. Silently returning fewer types is indistinguishable from an empty index.
	 */
	@Test
	public void testSearchNarrowsByTypePermission() throws LoomClientException {
		try (LoomHttpClient client = loginClientWith("gql-search-tag-only", Permission.READ_SEARCH, Permission.READ_TAG)) {
			Asset asset = seedAsset("gqlsearch_narrowing.mp4");
			Tag tag = seedTag("gqlsearch_narrowing");

			Map<String, Object> search = search(client, "gqlsearch_narrowing");

			assertTrue(containsHit(search, tag.getUuid()), "The caller may read tags, so the tag must be returned");
			assertFalse(containsHit(search, asset.getUuid()), "The caller may not read assets, so no asset may be returned");
			assertFalse(((List<?>) search.get("warnings")).isEmpty(), "A withheld type must be named in warnings");
		}
	}

	@Test
	public void testSearchWithoutAnyReadableTypeIsForbidden() throws LoomClientException {
		try (LoomHttpClient client = loginClientWith("gql-search-bare", Permission.READ_SEARCH)) {
			// READ_SEARCH alone permits the field but grants no readable type. A FORBIDDEN error is more honest
			// than an empty page, which would read as "nothing matched".
			GraphQLResponse response = query(client, SEARCH, q("anything"));
			assertErrorCode(response, "FORBIDDEN");
		}
	}

	@Override
	@Test
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		// search is a single field: there is no by-uuid variant, so both halves of the contract cover the same
		// field. Kept separate because the interface is what forces every domain to assert both.
		try (LoomHttpClient client = loginPermissionlessClient()) {
			assertRetrievalForbidden(client, Permission.READ_SEARCH, SEARCH, q("anything"));
		}
	}

	@Override
	@Test
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			assertRetrievalForbidden(client, Permission.READ_SEARCH,
				"query($q: String!) { search(q: $q, types: [ASSET], limit: 5) { totalHits } }", q("anything"));
		}
	}

	// --- request validation -----------------------------------------------------------------------

	/**
	 * A mode the provider cannot serve must fail with the reason, never quietly fall back to lexical results wearing a semantic label.
	 */
	@Test
	public void testUnsupportedModeIsRejectedNotSilentlyDowngraded() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			GraphQLResponse response = query(client,
				"query($q: String!) { search(q: $q, mode: SEMANTIC) { totalHits hits { entityUuid } } }", q("anything"));

			assertErrorCode(response, "BAD_USER_INPUT");
			JsonObject error = response.getErrors().getJsonObject(0);
			assertEquals(400, error.getJsonObject("extensions").getInteger("status"));
			assertTrue(error.getString("message").contains("SEMANTIC"), "The error must name the mode it cannot serve");
		}
	}

	@Test
	public void testBlankQueryIsRejected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			assertErrorCode(query(client, SEARCH, q("   ")), "BAD_USER_INPUT");
		}
	}

	@Test
	public void testOversizedQueryIsRejected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			assertErrorCode(query(client, SEARCH, q("x".repeat(600))), "BAD_USER_INPUT");
		}
	}

	@Test
	public void testUnknownEntityTypeIsRejectedBySchemaValidation() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The enum in the SDL is what rejects this, before any fetcher runs - so there is no custom code
			// extension here, only a plain GraphQL validation error.
			assertHasErrors(query(client, "{ search(q: \"anything\", types: [BANANA]) { totalHits } }"));
		}
	}
}
