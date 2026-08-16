package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The two search MCP tools, against a mocked {@link SearchProvider}.
 *
 * <p>
 * Both tools used to answer without asking anything: {@code search_assets} declared {@code query} and {@code mimeType} and then returned the first page
 * of the whole catalogue, and {@code search_transcript} returned a fixed sentence about Elasticsearch. What is pinned here is therefore not only that
 * the SPI is reached, but that a caller can tell the three outcomes apart — hits, nothing matched, and search is not answering.
 * </p>
 */
public class SearchToolTest {

	private static final UUID ASSET_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID TRANSCRIPT_UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
	private static final UUID LIBRARY_UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");

	private SearchProvider provider;

	@BeforeEach
	public void setup() {
		provider = mock(SearchProvider.class);
		when(provider.name()).thenReturn("postgres");
		when(provider.isAvailable()).thenReturn(true);
	}

	private void answerWith(SearchHit... hits) {
		SearchResult result = new SearchResult()
			.setHits(List.of(hits))
			.setTotalHits(hits.length)
			.setProviderName("postgres");
		when(provider.search(any())).thenReturn(result);
	}

	private SearchHit assetHit(String title, double score) {
		return new SearchHit()
			.setType(SearchEntityType.ASSET)
			.setUuid(ASSET_UUID)
			.setAssetUuid(ASSET_UUID)
			.setTitle(title)
			.setMimeType("video/mp4")
			.setSize(4711L)
			.setScore(score);
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getJsonArray("content");
		assertNotNull(content, "The tool result should carry content");
		return content.getJsonObject(0).getString("text");
	}

	private SearchRequest capturedRequest() {
		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(provider).search(captor.capture());
		return captor.getValue();
	}

	// --- search_assets -----------------------------------------------------------------------

	@Test
	public void testSearchAssetsDescriptor() {
		SearchAssetsTool tool = new SearchAssetsTool(provider);
		assertEquals("search_assets", tool.descriptor().name());
		assertEquals(List.of("READ_ASSET"), tool.descriptor().requiredPermissions());

		JsonObject schema = tool.descriptor().inputSchema();
		JsonObject properties = schema.getJsonObject("properties");
		assertTrue(properties.containsKey("offset"), "Paging must be reachable, not just the first page");
		assertTrue(properties.containsKey("library"), "The library filter must be declared");
		assertTrue(properties.containsKey("tag"), "The tag filter must be declared");
		assertEquals(List.of("query"), schema.getJsonArray("required").getList(),
			"A search without a term is not a search — the SPI rejects it, so the schema must demand it");
	}

	/** The term and every declared filter must reach the request. Declaring one and dropping it is the bug this replaces. */
	@Test
	public void testSearchAssetsPassesEveryDeclaredFilterToTheProvider() {
		answerWith(assetHit("bigbuckbunny.mp4", 0.8));

		new SearchAssetsTool(provider).execute(new JsonObject()
			.put("query", "sunset")
			.put("mimeType", "image/jpeg")
			.put("library", LIBRARY_UUID.toString())
			.put("tag", "holiday")
			.put("limit", 5)
			.put("offset", 10)).result();

		SearchRequest request = capturedRequest();
		assertEquals("sunset", request.getQuery());
		assertEquals(Set.of(SearchEntityType.ASSET), request.getTypes(), "The tool searches assets only");
		assertEquals("image/jpeg", request.getMimeTypePrefix());
		assertEquals(LIBRARY_UUID, request.getLibraryUuid());
		assertEquals(List.of("holiday"), request.getTags());
		assertEquals(5, request.getLimit());
		assertEquals(10, request.getOffset());
	}

	/**
	 * {@code video/*} is the conventional way to write "any video" and the index would match nothing against it — the provider filters
	 * {@code mime_type LIKE '<value>%'}. The wildcard is dropped rather than handed on as a guaranteed empty result.
	 */
	@Test
	public void testSearchAssetsNormalizesAWildcardMimeType() {
		answerWith();
		new SearchAssetsTool(provider).execute(new JsonObject().put("query", "clip").put("mimeType", "video/*")).result();
		assertEquals("video/", capturedRequest().getMimeTypePrefix());
	}

	@Test
	public void testSearchAssetsReturnsOnlyWhatMatched() {
		answerWith(assetHit("bigbuckbunny.mp4", 0.42));

		JsonObject result = new SearchAssetsTool(provider).execute(new JsonObject().put("query", "bunny")).result();

		String body = text(result);
		assertTrue(body.contains("Found 1 of 1 matching assets for 'bunny'."), body);
		assertTrue(body.contains("bigbuckbunny.mp4"), "The title should be in the text the model sees");
		assertTrue(body.contains("video/mp4"), "So should the mime type");
		assertTrue(body.contains("0.42"), "And the score, so the model can weigh the hits");

		JsonArray references = result.getJsonArray("references");
		assertNotNull(references, "A hit should be referencable as an entity chip");
		assertEquals(1, references.size());
		assertEquals("asset", references.getJsonObject(0).getString("type"));
		assertEquals(ASSET_UUID.toString(), references.getJsonObject(0).getString("uuid"));
		assertEquals("bigbuckbunny.mp4", references.getJsonObject(0).getString("label"));
	}

	/** Zero hits must read as zero hits — the old tool answered an arbitrary page of the catalogue instead. */
	@Test
	public void testSearchAssetsWithNoMatchesReturnsNothingRatherThanAPage() {
		answerWith();

		JsonObject result = new SearchAssetsTool(provider).execute(new JsonObject().put("query", "nothing-matches-this")).result();

		assertEquals("No assets matched 'nothing-matches-this'.", text(result));
		assertNull(result.getJsonArray("references"), "Nothing matched, so there is nothing to reference");
	}

	@Test
	public void testSearchAssetsWithoutAQuery() {
		assertTrue(new SearchAssetsTool(provider).execute(new JsonObject()).failed(), "A missing query should fail the call");
		assertTrue(new SearchAssetsTool(provider).execute(new JsonObject().put("query", "  ")).failed(), "So should a blank one");
	}

	/** A malformed uuid answers rather than throwing, so the model can correct itself. */
	@Test
	public void testSearchAssetsWithAMalformedLibraryUuid() {
		JsonObject result = new SearchAssetsTool(provider)
			.execute(new JsonObject().put("query", "sunset").put("library", "not-a-uuid")).result();
		assertTrue(text(result).contains("Not a uuid"), text(result));
	}

	// --- search_transcript -------------------------------------------------------------------

	@Test
	public void testSearchTranscriptDescriptor() {
		SearchTranscriptTool tool = new SearchTranscriptTool(provider);
		assertEquals("search_transcript", tool.descriptor().name());
		assertEquals(List.of("READ_ASSET"), tool.descriptor().requiredPermissions());
		assertFalse(tool.descriptor().description().contains("Elasticsearch"),
			"The tool is served by the search SPI; the stub's excuse must not survive in the model's prompt");
	}

	@Test
	public void testSearchTranscriptNarrowsToTranscriptsAndAsksForHighlights() {
		answerWith();
		new SearchTranscriptTool(provider).execute(new JsonObject().put("query", "quarterly")).result();

		SearchRequest request = capturedRequest();
		assertEquals(Set.of(SearchEntityType.TRANSCRIPT), request.getTypes());
		assertTrue(request.isHighlight(), "Without highlights there is no snippet to return");
		assertEquals(10, request.getLimit(), "The documented default");
	}

	/** The point of a transcript hit: a snippet the model can quote, plus the asset and offset it can deep-link to. */
	@Test
	public void testSearchTranscriptReturnsSnippetAssetAndOffset() {
		answerWith(new SearchHit()
			.setType(SearchEntityType.TRANSCRIPT)
			.setUuid(TRANSCRIPT_UUID)
			.setAssetUuid(ASSET_UUID)
			.setTitle("bigbuckbunny.mp4")
			.setTimeFromMs(94000L)
			.setHighlights(List.of("the <b>quarterly</b> figures were"))
			.setScore(0.5));

		JsonObject result = new SearchTranscriptTool(provider).execute(new JsonObject().put("query", "quarterly")).result();

		String body = text(result);
		assertTrue(body.contains("the quarterly figures were"), body);
		assertFalse(body.contains("<b>"), "ts_headline markup is unsanitised source text and must not be relayed");
		assertTrue(body.contains(ASSET_UUID.toString()), "The asset must be named so the caller can navigate to it");
		assertTrue(body.contains("94000"), "The offset is what makes a transcript hit deep-linkable");

		JsonArray references = result.getJsonArray("references");
		assertNotNull(references, "A transcript hit references the asset it belongs to");
		assertEquals(ASSET_UUID.toString(), references.getJsonObject(0).getString("uuid"));
	}

	/** Highlighting is best-effort in the provider, so a hit can arrive without one. It still has to say something. */
	@Test
	public void testSearchTranscriptFallsBackToTheSubtitleWithoutAHighlight() {
		answerWith(new SearchHit()
			.setType(SearchEntityType.TRANSCRIPT)
			.setUuid(TRANSCRIPT_UUID)
			.setAssetUuid(ASSET_UUID)
			.setTitle("bigbuckbunny.mp4")
			.setSubtitle("en · whisper-large-v3")
			.setTimeFromMs(0L));

		String body = text(new SearchTranscriptTool(provider).execute(new JsonObject().put("query", "quarterly")).result());
		assertTrue(body.contains("en · whisper-large-v3"), body);
	}

	@Test
	public void testSearchTranscriptWithNoMatches() {
		answerWith();
		JsonObject result = new SearchTranscriptTool(provider).execute(new JsonObject().put("query", "unsaid")).result();
		assertEquals("No transcript matched 'unsaid'.", text(result));
	}

	@Test
	public void testSearchTranscriptWithoutAQuery() {
		assertTrue(new SearchTranscriptTool(provider).execute(new JsonObject()).failed(), "A missing query should fail the call");
	}

	// --- degradation -------------------------------------------------------------------------

	/**
	 * An unavailable provider must not read as an empty catalogue. {@code NoopSearchProvider.search()} throws a 503, so the tools check availability
	 * first and answer with the reason rather than handing the model a stack trace or a confident "nothing found".
	 */
	@Test
	public void testAnUnavailableProviderIsReportedHonestly() {
		when(provider.isAvailable()).thenReturn(false);
		when(provider.name()).thenReturn("none");
		when(provider.info()).thenReturn(new SearchProviderInfo()
			.setProvider("none")
			.setAvailable(false)
			.setReason("Search is disabled on this deployment."));

		for (JsonObject result : List.of(
			new SearchAssetsTool(provider).execute(new JsonObject().put("query", "sunset")).result(),
			new SearchTranscriptTool(provider).execute(new JsonObject().put("query", "sunset")).result())) {
			String body = text(result);
			assertTrue(body.contains("Search is unavailable"), body);
			assertTrue(body.contains("Search is disabled on this deployment."), "The reason from info() must reach the caller");
			assertFalse(body.contains("Found "), body);
			assertNull(result.getJsonArray("references"));
		}

		verify(provider, never()).search(any());
	}

	/** A rejected query (blank, oversized, offset past the deep-paging cap) is something the model can fix, so it comes back as text. */
	@Test
	public void testAProviderRejectionBecomesAnAnswer() {
		when(provider.search(any()))
			.thenThrow(new LoomRestException(400, LoomRestErrorCode.SEARCH_UNSUPPORTED, "The offset exceeds the maximum of 1000."));

		JsonObject result = new SearchAssetsTool(provider).execute(new JsonObject().put("query", "sunset").put("offset", 5000)).result();
		String body = text(result);
		assertTrue(body.contains("The search could not be run"), body);
		assertTrue(body.contains("The offset exceeds the maximum of 1000."), body);
	}

}
