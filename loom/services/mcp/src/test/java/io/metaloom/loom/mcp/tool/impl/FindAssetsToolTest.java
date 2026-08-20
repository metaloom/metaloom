package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.tool.impl.search.SearchVocabulary;
import io.metaloom.loom.mcp.tool.impl.search.SearchVocabulary.Match;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@code find_assets} end to end over a mocked provider and vocabulary.
 *
 * <p>
 * The three outcomes a caller must be able to tell apart are asserted separately: hits, nothing matched, and the search did not run. The last one is
 * the reason this class exists - a tool that answers "no assets" when search is switched off makes the model report an empty catalogue with total
 * confidence, and there is nothing downstream that can catch it.
 * </p>
 */
public class FindAssetsToolTest {

	private static final UUID ASSET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID CALLER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final UUID PETE = UUID.fromString("11111111-0000-0000-0000-000000000001");

	private static final MCPCallerContext CTX = new MCPCallerContext(CALLER, "alice", Set.of(), null, null);

	private SearchProvider provider;

	private SearchVocabulary vocabulary;

	private FindAssetsTool tool;

	@BeforeEach
	public void setup() {
		provider = mock(SearchProvider.class);
		when(provider.name()).thenReturn("postgres");
		when(provider.isAvailable()).thenReturn(true);
		vocabulary = mock(SearchVocabulary.class);
		tool = new FindAssetsTool(provider, vocabulary, new LoomOptions());
	}

	private void answerWith(SearchHit... hits) {
		when(provider.search(any())).thenReturn(new SearchResult()
			.setHits(List.of(hits))
			.setTotalHits(hits.length)
			.setProviderName("postgres"));
	}

	private static SearchHit assetHit(UUID uuid, String title) {
		return new SearchHit()
			.setType(SearchEntityType.ASSET)
			.setUuid(uuid)
			.setAssetUuid(uuid)
			.setTitle(title)
			.setMimeType("image/jpeg")
			.setSize(4711L)
			.setSortDate(Instant.parse("2026-08-19T08:00:00Z"))
			.setScore(0.9);
	}

	private JsonObject call(JsonObject arguments) {
		Future<JsonObject> future = tool.execute(arguments, CTX);
		assertTrue(future.succeeded(), "The tool answers rather than failing: " + future.cause());
		return future.result();
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getJsonArray("content");
		assertNotNull(content, "The tool result should carry content");
		return content.getJsonObject(0).getString("text");
	}

	// --- the descriptor ---------------------------------------------------------------------------

	@Test
	public void testItIsIdentityScoped() {
		MCPToolDescriptor descriptor = tool.descriptor();
		// Without this the tool gets an EventBus address and can be reached with no caller at all, which
		// is precisely the hole search_assets documents in its own javadoc.
		assertTrue(descriptor.requiresIdentity());
		assertTrue(descriptor.requiredPermissions().contains("READ_ASSET"));
		assertTrue(descriptor.requiredPermissions().contains("READ_SEARCH"));
	}

	@Test
	public void testTheIdentityFreeOverloadRefuses() {
		Future<JsonObject> future = tool.execute(new JsonObject().put("text", "x"));
		assertTrue(future.failed());
	}

	@Test
	public void testTheSchemaAdvertisesExactlyWhatIsAccepted() {
		JsonObject properties = tool.descriptor().inputSchema().getJsonObject("properties");
		// The schema is the only description of the vocabulary the model ever sees; a field the validator
		// accepts but the schema omits is a field the model will never use.
		for (String key : List.of("text", "creator", "collection", "library", "space", "tags",
			"when", "createdFrom", "createdTo", "mimeType", "types", "sort", "mode", "highlight", "timezone", "limit", "offset")) {
			assertTrue(properties.containsKey(key), "The schema is missing " + key);
		}
		// Nothing is required: filters alone are a valid query.
		assertFalse(tool.descriptor().inputSchema().containsKey("required"));
	}

	// --- the query that reaches the provider ------------------------------------------------------

	@Test
	public void testNamesAreResolvedBeforeTheQueryIsBuilt() {
		when(vocabulary.resolveUser("pete")).thenReturn(Match.of(PETE, "Pete Miller (pete)"));
		answerWith(assetHit(ASSET, "harbour.jpg"));

		JsonObject result = call(new JsonObject()
			.put("creator", "pete")
			.put("when", "2026-08-19")
			.put("timezone", "UTC"));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(provider).search(captor.capture());
		SearchRequest request = captor.getValue();
		assertEquals(PETE, request.getCreatorUuid(), "The uuid, not the word the model sent");
		assertEquals(Instant.parse("2026-08-19T00:00:00Z"), request.getCreatedFrom());
		// Identity comes from the resolved context, never from the arguments.
		assertEquals(CALLER, request.getUserUuid());
		assertTrue(text(result).contains("Pete Miller"), "The answer reports what was applied: " + text(result));
	}

	@Test
	public void testTheCallerCannotSpoofIdentityThroughTheArguments() {
		answerWith(assetHit(ASSET, "harbour.jpg"));
		// userUuid is not in the accepted key set, so an attempt to set it is a refusal, not an override.
		JsonObject result = call(new JsonObject().put("text", "harbour").put("userUuid", UUID.randomUUID().toString()));
		assertTrue(text(result).contains("Unknown parameter"), text(result));
		verify(provider, never()).search(any());
	}

	// --- the three outcomes -----------------------------------------------------------------------

	@Test
	public void testHitsAreListedWithTheirUuidsAndReferences() {
		answerWith(assetHit(ASSET, "harbour.jpg"));
		JsonObject result = call(new JsonObject().put("text", "harbour"));
		String text = text(result);
		assertTrue(text.contains("Found 1 asset"), text);
		assertTrue(text.contains(ASSET.toString()));
		assertTrue(text.contains("harbour.jpg"));
		JsonArray references = result.getJsonArray("references");
		assertEquals(1, references.size(), "Hits become entity chips in the chat UI");
		assertEquals("asset", references.getJsonObject(0).getString("type"));
	}

	@Test
	public void testAnEmptyResultRestatesWhatWasSearchedFor() {
		when(vocabulary.resolveUser("pete")).thenReturn(Match.of(PETE, "Pete Miller (pete)"));
		answerWith();
		String text = text(call(new JsonObject().put("creator", "pete").put("text", "harbour")));
		assertTrue(text.startsWith("No assets matched"), text);
		// Without the criteria the model reports "there are no harbour photos" when in fact it asked for
		// Pete's harbour photos.
		assertTrue(text.contains("Pete Miller"), text);
		assertTrue(text.contains("harbour"), text);
	}

	@Test
	public void testAnUnavailableProviderIsNotAnEmptyResult() {
		when(provider.isAvailable()).thenReturn(false);
		when(provider.info()).thenReturn(new SearchProviderInfo().setProvider("none").setAvailable(false).setReason("search is disabled"));
		String text = text(call(new JsonObject().put("text", "harbour")));
		assertTrue(text.contains("unavailable"), text);
		assertTrue(text.contains("do not report it as 'no results'"), text);
		verify(provider, never()).search(any());
	}

	@Test
	public void testAProviderRejectionComesBackAsReadableText() {
		when(provider.search(any())).thenThrow(new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "A search term (q) is required."));
		String text = text(call(new JsonObject().put("text", "harbour")));
		assertTrue(text.contains("could not be run"), text);
	}

	// --- rendering ---------------------------------------------------------------------------------

	@Test
	public void testATranscriptHitDoesNotDuplicateItsAsset() {
		SearchHit transcript = new SearchHit()
			.setType(SearchEntityType.TRANSCRIPT)
			.setUuid(UUID.randomUUID())
			.setAssetUuid(ASSET)
			.setTitle("harbour.mp4")
			.setTimeFromMs(12000L);
		answerWith(assetHit(ASSET, "harbour.mp4"), transcript);
		String text = text(call(new JsonObject().put("text", "harbour").put("types", new JsonArray().add("asset").add("transcript"))));
		// Two rows, one file. Listing it twice reads as two files.
		assertTrue(text.contains("Found 1 asset"), text);
	}

	@Test
	public void testACollapsedTranscriptStillContributesItsTimecode() {
		// The only reason to ask for transcripts is to learn where in the timeline the words were said.
		// Deduping must not throw that away just because the asset row happened to rank first.
		SearchHit transcript = new SearchHit()
			.setType(SearchEntityType.TRANSCRIPT)
			.setUuid(UUID.randomUUID())
			.setAssetUuid(ASSET)
			.setTitle("harbour.mp4")
			.setTimeFromMs(12000L);
		answerWith(assetHit(ASSET, "harbour.mp4"), transcript);
		String text = text(call(new JsonObject().put("text", "harbour").put("types", new JsonArray().add("asset").add("transcript"))));
		assertTrue(text.contains("12000"), "The timecode of the collapsed row survives: " + text);
	}

	@Test
	public void testAPagedResultSaysHowManyThereAreInTotal() {
		when(provider.search(any())).thenReturn(new SearchResult()
			.setHits(List.of(assetHit(ASSET, "harbour.jpg")))
			.setTotalHits(412)
			.setProviderName("postgres"));
		String text = text(call(new JsonObject().put("text", "harbour").put("limit", 1)));
		assertTrue(text.startsWith("Showing 1 of 412 matches"), text);
	}

	@Test
	public void testARefusalNeverReachesTheProvider() {
		when(vocabulary.resolveUser("pete")).thenReturn(Match.none());
		String text = text(call(new JsonObject().put("creator", "pete")));
		assertTrue(text.startsWith("Could not run the search"), text);
		verify(provider, never()).search(any());
	}

}
