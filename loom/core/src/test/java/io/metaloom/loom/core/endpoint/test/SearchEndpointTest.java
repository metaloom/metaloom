package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.search.SearchHitResponse;
import io.metaloom.loom.rest.model.search.SearchResultResponse;
import io.metaloom.loom.rest.model.search.SearchStatusResponse;
import io.metaloom.loom.rest.model.search.SearchSuggestionListResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Endpoint behaviour of {@code /api/v1/search/*}.
 *
 * <p>
 * Extends {@link AbstractEndpointTest} rather than the CRUD base class: search is not a CRUD resource - there is no create/update/delete, and ranked
 * results are structurally incompatible with the keyset paging the CRUD list path uses.
 * </p>
 */
public class SearchEndpointTest extends AbstractEndpointTest {

	private static final UUID USER_UUID = io.metaloom.loom.test.data.TestValues.USER_UUID;

	private static final UUID ADMIN_UUID = io.metaloom.loom.test.data.TestValues.ADMIN_UUID;

	// --- fixtures ---------------------------------------------------------------------------------

	private Asset seedAsset(String filename) {
		DaoCollection daos = daos();
		Asset asset = daos.assetDao().createAsset(adminUuid(), SHA512.fromString(randomSha512()),
			"video/mp4", filename, "/media/" + filename, 2048L);
		daos.assetDao().store(asset);
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

	/**
	 * Log the {@code joedoe} fixture user in, holding exactly the given permissions.
	 *
	 * <p>
	 * Granted via role -&gt; group -&gt; user, never as a direct user grant: {@code user_permission} is keyed by {@code user_uuid} alone, so a user can
	 * hold exactly one direct grant and a multi-permission fixture built that way would silently keep only the last one.
	 * </p>
	 */
	private LoomHttpClient loginWith(String roleName, Permission... permissions) throws LoomClientException {
		DaoCollection daos = daos();
		User joedoe = daos.userDao().load(USER_UUID);
		Role role = daos.roleDao().createRole(ADMIN_UUID, roleName);
		daos.roleDao().store(role);
		for (Permission permission : permissions) {
			daos.permissionDao().grantRolePermission(role.getUuid(), permission, "test");
		}
		Group group = daos.groupDao().create(joedoe, roleName + "-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		LoomHttpClient client = httpClient();
		AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
		client.setToken(login.getToken());
		return client;
	}

	// --- happy path -------------------------------------------------------------------------------

	@Test
	public void testSearchFindsAnAsset() throws LoomClientException {
		Asset asset = seedAsset("kittiwake_colony.mp4");
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		SearchResultResponse response = client.search("kittiwake").sync().body();
		assertNotNull(response.getMetainfo(), "The response must carry a metainfo block");
		assertEquals("postgres", response.getMetainfo().getProvider());
		assertTrue(response.getMetainfo().getTotalHits() >= 1);
		assertTrue(response.getData().stream().anyMatch(hit -> asset.getUuid().equals(hit.getUuid())));
	}

	@Test
	public void testSearchAssetsRestrictsToAssets() throws LoomClientException {
		seedAsset("restricted_probe.mp4");
		seedTag("restricted_probe");
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		SearchResultResponse response = client.searchAssets("restricted_probe").sync().body();
		assertFalse(response.getData().isEmpty());
		for (SearchHitResponse hit : response.getData()) {
			assertEquals("asset", hit.getType(), "The asset route must only return assets");
		}
	}

	@Test
	public void testSuggestions() throws LoomClientException {
		seedTag("suggestable");
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		SearchSuggestionListResponse response = client.searchSuggestions("suggest").sync().body();
		assertNotNull(response.getData());
		assertTrue(response.getData().stream().anyMatch(s -> "suggestable".equals(s.getText())));
	}

	@Test
	public void testStatus() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		SearchStatusResponse status = client.searchStatus().sync().body();
		assertEquals("postgres", status.getProvider());
		assertTrue(status.isAvailable());
		assertTrue(status.getCapabilities().contains("LEXICAL"));
		assertFalse(status.getCapabilities().contains("SEMANTIC"), "The status must not advertise a capability the provider lacks");
	}

	@Test
	public void testPaging() throws LoomClientException {
		for (int i = 0; i < 4; i++) {
			seedAsset("pageprobe_" + i + ".mp4");
		}
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		SearchResultResponse first = client.search("pageprobe", "limit", "2", "offset", "0").sync().body();
		SearchResultResponse second = client.search("pageprobe", "limit", "2", "offset", "2").sync().body();

		assertEquals(2, first.getData().size());
		assertEquals(first.getMetainfo().getTotalHits(), second.getMetainfo().getTotalHits(),
			"totalHits must count every match and stay stable across pages");
		assertTrue(first.getMetainfo().getTotalHits() >= 4);
		for (SearchHitResponse hit : second.getData()) {
			assertFalse(first.getData().stream().anyMatch(h -> h.getUuid().equals(hit.getUuid())), "Pages must not overlap");
		}
	}

	@Test
	public void testHighlighting() throws LoomClientException {
		seedAsset("highlightable_clip.mp4");
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		SearchResultResponse response = client.search("highlightable", "highlight", "true").sync().body();
		SearchHitResponse hit = response.getData().get(0);
		assertNotNull(hit.getMatchedIn(), "A highlighted search must report which field matched");
		assertFalse(hit.getHighlights().isEmpty(), "A highlighted search must return a snippet");
	}

	// --- permissions ------------------------------------------------------------------------------

	@Test
	public void testSearchRequiresPermission() throws LoomClientException {
		LoomHttpClient nobody = loginPermissionlessClient();
		expect(403, "Forbidden", nobody.search("anything"));
		expect(403, "Forbidden", nobody.searchAssets("anything"));
		expect(403, "Forbidden", nobody.searchSuggestions("any"));
		expect(403, "Forbidden", nobody.searchStatus());
	}

	/**
	 * The permission semantic that is new in this feature.
	 *
	 * <p>
	 * A caller holding {@code READ_SEARCH} and {@code READ_TAG} but not {@code READ_ASSET} must get tag hits and no asset hits, and must be told that
	 * something was withheld. Returning fewer types silently would be indistinguishable from an empty index.
	 * </p>
	 */
	@Test
	public void testSearchNarrowsByTypePermission() throws LoomClientException {
		Asset asset = seedAsset("narrowing_probe.mp4");
		Tag tag = seedTag("narrowing_probe");

		LoomHttpClient client = loginWith("search-tag-only", Permission.READ_SEARCH, Permission.READ_TAG);
		SearchResultResponse response = client.search("narrowing_probe").sync().body();

		assertTrue(response.getData().stream().anyMatch(hit -> tag.getUuid().equals(hit.getUuid())),
			"The caller may read tags, so the tag must be returned");
		assertFalse(response.getData().stream().anyMatch(hit -> asset.getUuid().equals(hit.getUuid())),
			"The caller may not read assets, so no asset may be returned");
		assertFalse(response.getMetainfo().getWarnings().isEmpty(),
			"Withholding a type must be reported rather than silently reducing the result set");
	}

	@Test
	public void testSearchAssetsRequiresReadAsset() throws LoomClientException {
		LoomHttpClient client = loginWith("search-no-asset", Permission.READ_SEARCH, Permission.READ_TAG);
		expect(403, "Forbidden", client.searchAssets("anything"));
	}

	@Test
	public void testSearchWithOnlyReadSearchReturnsForbidden() throws LoomClientException {
		// READ_SEARCH alone permits the route but grants no readable type, so there is nothing to answer
		// with. A 403 is more honest than an empty page.
		LoomHttpClient client = loginWith("search-bare", Permission.READ_SEARCH);
		expect(403, "Forbidden", client.search("anything"));
	}

	// --- request validation -----------------------------------------------------------------------

	@Test
	public void testMissingQueryParameterIsRejected() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		expect(400, "Bad Request", client.search(null));
	}

	@Test
	public void testOversizedQueryIsRejected() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		expect(400, "Bad Request", client.search("x".repeat(600)));
	}

	@Test
	public void testOffsetPastTheCapIsRejected() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		// Deep paging degrades into a table scan on this provider, so it is refused up front rather
		// than accepted and left to time out.
		expect(400, "Bad Request", client.search("anything", "offset", "100000"));
	}

	@Test
	public void testUnsupportedModeIsRejectedNotSilentlyDowngraded() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		expect(400, "Bad Request", client.search("anything", "mode", "SEMANTIC"));
	}

	@Test
	public void testUnknownEntityTypeIsRejected() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		expect(400, "Bad Request", client.search("anything", "types", "banana"));
	}

	@Test
	public void testMalformedQueriesDoNotError() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		for (String query : List.of("&&&", "'", "!!!", "\"unclosed", ":*")) {
			assertNotNull(client.search(query).sync().body(), "Query '" + query + "' must not produce an error response");
		}
	}
}
