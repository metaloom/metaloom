package io.metaloom.loom.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.metaloom.utils.hash.SHA512;

/**
 * {@code find_assets} against the real registry, the real search backend and the real database.
 *
 * <p>
 * The unit tests pin what the tool renders against a mocked provider. What only a wired Loom can answer is whether the name a person used actually
 * reaches a uuid and narrows a query - the whole feature is that translation, and every part of it (the DAO scan, the trigger-maintained
 * {@code search_document}, the correlated creator lookup in {@code PostgresSearchProvider}) is invisible to a mock.
 * </p>
 *
 * <p>
 * Run {@code ./setup-pool.sh} first.
 * </p>
 */
public class MCPFindAssetsTest implements TestValues {

	private static final String TOOL = "find_assets";

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension();

	private MCPToolRegistry registry() {
		return loom.internal().boot().getMcpService().getToolRegistry();
	}

	private DaoCollection daos() {
		return loom.internal().daos();
	}

	private User adminUser() {
		return User.create(new JsonObject().put("uuid", ADMIN_UUID.toString()));
	}

	private User unprivilegedUser() {
		return User.create(new JsonObject().put("uuid", UUID.randomUUID().toString()));
	}

	private MCPCallerContext ctxOf(User user) {
		return new MCPCallerContext(UUID.fromString(user.principal().getString("uuid")), "test", Set.of(), null, null);
	}

	/** A file nothing else in the fixture is named like, so a hit is this asset and not a lucky match. */
	private Asset seedAsset(UUID creatorUuid, String filename) {
		String sha512 = UUID.randomUUID().toString().replace("-", "").repeat(4);
		Asset asset = daos().assetDao().createAsset(creatorUuid, SHA512.fromString(sha512), "image/jpeg", filename, "/media/" + filename, 2048L);
		daos().assetDao().store(asset);
		return asset;
	}

	private JsonObject call(JsonObject arguments) throws Exception {
		return await(registry().dispatch(TOOL, arguments, adminUser(), ctxOf(adminUser())));
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getJsonArray("content");
		assertNotNull(content, "The tool result should carry content");
		return content.getJsonObject(0).getString("text");
	}

	// --- wiring -----------------------------------------------------------------------------------

	@Test
	public void testTheToolIsRegistered() throws Exception {
		List<String> names = await(registry().listDescriptorsFor(adminUser())).stream().map(MCPToolDescriptor::name).toList();
		assertThat(names).contains(TOOL);
	}

	@Test
	public void testItHasNoEventBusAddress() {
		// Identity-scoped tools are dispatched in-process only. An address here would mean an anonymous
		// external MCP client could search the catalogue with no caller at all.
		MCPToolDescriptor descriptor = registry().getDescriptor(TOOL);
		assertNotNull(descriptor);
		assertTrue(descriptor.requiresIdentity());
	}

	@Test
	public void testUnprivilegedCallerIsNeitherToldNorAllowed() throws Exception {
		User user = unprivilegedUser();
		List<String> names = await(registry().listDescriptorsFor(user)).stream().map(MCPToolDescriptor::name).toList();
		assertFalse(names.contains(TOOL), "A caller without READ_SEARCH/READ_ASSET must not be told about " + TOOL);

		Throwable failure = failureOf(registry().dispatch(TOOL, new JsonObject().put("text", "anything"), user, ctxOf(user)));
		assertNotNull(failure, "The call itself must be refused, not merely hidden");
		assertTrue(failure.getMessage().contains("Missing required permissions"), failure.getMessage());
	}

	@Test
	public void testAnonymousCallerIsRefused() throws Exception {
		Throwable failure = failureOf(registry().dispatch(TOOL, new JsonObject().put("text", "anything"), null, MCPCallerContext.ANONYMOUS));
		assertNotNull(failure);
		assertTrue(failure.getMessage().contains("requires an authenticated caller"), failure.getMessage());
	}

	// --- the translation itself -------------------------------------------------------------------

	@Test
	public void testACreatorNameNarrowsToThatUsersAssets() throws Exception {
		// One plain token, no hyphen: search_tokenize_path translates / \\ _ - . to spaces, so the
		// separators here become token boundaries and "vocabularyprobe" is a token of its own in both
		// documents. A hyphenated probe term would be parsed as a phrase and match neither.
		String probe = "vocabularyprobe";
		String mine = probe + "-joe-" + UUID.randomUUID() + ".jpg";
		String theirs = probe + "-admin-" + UUID.randomUUID() + ".jpg";
		seedAsset(USER_UUID, mine);
		seedAsset(ADMIN_UUID, theirs);

		// "joedoe" is a username the fixture owns; nothing here passes a uuid.
		String text = text(call(new JsonObject().put("text", probe).put("creator", "joedoe")));

		assertTrue(text.contains(mine), "Joe's asset should be found: " + text);
		assertFalse(text.contains(theirs), "The admin's asset must be excluded by the creator filter: " + text);
		assertTrue(text.contains("joedoe"), "The answer names who it resolved to: " + text);
	}

	@Test
	public void testFiltersAloneAreAValidQuery() throws Exception {
		// The user's own example - "everything pete uploaded today" - carries no search term at all. This
		// is the case PostgresSearchProvider used to refuse outright with "A search term (q) is required".
		String filename = "termless-probe-" + UUID.randomUUID() + ".jpg";
		seedAsset(USER_UUID, filename);

		String text = text(call(new JsonObject()
			.put("creator", "joedoe")
			.put("when", "today")
			.put("mimeType", "image/")));

		assertFalse(text.startsWith("Could not run"), text);
		assertTrue(text.contains(filename), "A termless, filtered query must still find the asset: " + text);
	}

	@Test
	public void testAnUnknownNameRefusesRatherThanReturningEverything() throws Exception {
		seedAsset(ADMIN_UUID, "unknown-creator-probe-" + UUID.randomUUID() + ".jpg");
		String text = text(call(new JsonObject().put("creator", "nobody-by-that-name")));
		// The dangerous alternative is running without the clause and reporting the whole catalogue as
		// that person's work.
		assertTrue(text.startsWith("Could not run the search"), text);
		assertTrue(text.contains("nobody-by-that-name"), text);
	}

	@Test
	public void testAnUnknownParameterIsRefusedWithTheAcceptedOnes() throws Exception {
		String text = text(call(new JsonObject().put("text", "anything").put("uploadedBy", "joedoe")));
		assertTrue(text.contains("uploadedBy"), text);
		assertTrue(text.contains("creator"), "The refusal must point at the right key: " + text);
	}

	@Test
	public void testAFutureDateWindowFindsNothingAndSaysWhatItSearchedFor() throws Exception {
		seedAsset(USER_UUID, "future-probe-" + UUID.randomUUID() + ".jpg");
		String text = text(call(new JsonObject().put("creator", "joedoe").put("when", "2001-01-01")));
		assertTrue(text.startsWith("No assets matched"), text);
		// Restating the criteria is what stops "there are none" being read as "the catalogue is empty".
		assertTrue(text.contains("joedoe"), text);
	}

	private static Throwable failureOf(Future<JsonObject> future) throws Exception {
		try {
			future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
			return null;
		} catch (java.util.concurrent.ExecutionException e) {
			return e.getCause();
		}
	}

	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
	}

}
