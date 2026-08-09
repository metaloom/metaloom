package io.metaloom.loom.db.jooq.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.NoopTextEmbedder;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.SearchSuggestion;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Query grammar, ranking, paging and request validation of {@link PostgresSearchProvider}.
 *
 * <p>
 * Indexing coverage lives in {@code SearchDocumentSourceTest}; trigger and rebuild guarantees in {@code SearchDocumentLifecycleTest}.
 * </p>
 */
public class SearchQueryBehaviourTest extends AbstractJooqTest {

	private PostgresSearchProvider provider;

	private SearchOptions options;

	@BeforeEach
	public void setupProvider() {
		options = new SearchOptions();
		provider = new PostgresSearchProvider(ctx(), options, new NoopTextEmbedder("semantic search is off in this test"), new InMemoryVectorIndex());
	}

	private DSLContext ctx() {
		return context.ctx();
	}

	// --- fixtures ---------------------------------------------------------------------------------

	private Asset storeAsset(String filename, String origin) {
		User user = adminUser();
		Asset asset = assetDao().createAsset(user, SHA512.fromString(randomSha512()), "video/mp4", filename, origin, 1024L);
		assetDao().store(asset);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	private void storeTranscript(Asset asset, String lang, String text) {
		AssetTranscriptComp comp = daos().assetComponentDao().createTranscriptComp(adminUser().getUuid(), asset.getUuid(), "whisper");
		comp.setLang(lang).setModel("whisper-large-v3").setTranscriptText(text);
		daos().assetComponentDao().upsertTranscriptComp(comp);
	}

	private void storeJsonComp(Asset asset, String nodeKind, String schemaType, JsonObject data) {
		AssetJsonComp comp = daos().assetComponentDao().createJsonComp(adminUser().getUuid(), asset.getUuid(), nodeKind);
		comp.setSchemaType(schemaType).setData(data);
		daos().assetComponentDao().upsertJsonComp(comp);
	}

	private SearchResult search(String query) {
		return provider.search(new SearchRequest().setQuery(query).setLimit(50));
	}

	private boolean hits(SearchResult result, UUID uuid) {
		return result.getHits().stream().anyMatch(hit -> uuid.equals(hit.getUuid()));
	}

	private boolean hitsAsset(SearchResult result, UUID assetUuid) {
		return result.getHits().stream().anyMatch(hit -> assetUuid.equals(hit.getAssetUuid()));
	}

	// --- query behaviour --------------------------------------------------------------------------

	@Test
	public void testStemming() {
		Asset asset = storeAsset("stem.mp4", "/media/stem.mp4");
		storeTranscript(asset, "en", "The horses were running along the beach.");
		// Without the english-config column, searching "run" would not match indexed "running" - the
		// first thing a user reports as a bug.
		assertTrue(hitsAsset(search("run"), asset.getUuid()), "Searching 'run' must match indexed 'running'");
	}

	@Test
	public void testPhraseQuery() {
		Asset asset = storeAsset("phrase.mp4", "/media/phrase.mp4");
		storeTranscript(asset, "en", "the northern lights were visible");
		assertTrue(hitsAsset(search("\"northern lights\""), asset.getUuid()), "Quoted phrases must match");
		assertFalse(hitsAsset(search("\"lights northern\""), asset.getUuid()), "A phrase in the wrong order must not match");
	}

	@Test
	public void testNegation() {
		Asset keep = storeAsset("keepme.mp4", "/media/keepme.mp4");
		Asset drop = storeAsset("dropme.mp4", "/media/dropme.mp4");
		storeTranscript(keep, "en", "a glacier at sunrise");
		storeTranscript(drop, "en", "a glacier with a helicopter");

		SearchResult result = search("glacier -helicopter");
		assertTrue(hitsAsset(result, keep.getUuid()), "The non-excluded asset must still match");
		assertFalse(hitsAsset(result, drop.getUuid()), "The excluded term must remove its asset");
	}

	@Test
	public void testTypoTolerance() {
		Asset asset = storeAsset("Mercedes_ad.mp4", "/media/Mercedes_ad.mp4");
		assertTrue(hits(search("Merceds"), asset.getUuid()), "A one-character typo must still find the asset via trigram similarity");
	}

	@Test
	public void testTitleOutranksBody() {
		Asset titled = storeAsset("kestrel.mp4", "/media/kestrel.mp4");
		Asset bodied = storeAsset("other.mp4", "/media/other.mp4");
		storeTranscript(bodied, "en", "we watched a kestrel hover over the field for a while");

		SearchResult result = search("kestrel");
		int titleRank = indexOfAsset(result, titled.getUuid());
		int bodyRank = indexOfAsset(result, bodied.getUuid());
		assertTrue(titleRank >= 0 && bodyRank >= 0, "Both assets should match");
		assertTrue(titleRank < bodyRank, "A weight-A title match must outrank a weight-C body match");
	}

	private int indexOfAsset(SearchResult result, UUID assetUuid) {
		List<SearchHit> hits = result.getHits();
		for (int i = 0; i < hits.size(); i++) {
			if (assetUuid.equals(hits.get(i).getAssetUuid()) && hits.get(i).getType() == SearchEntityType.ASSET) {
				return i;
			}
		}
		return -1;
	}

	@Test
	public void testMalformedQueriesDoNotError() {
		// websearch_to_tsquery is the only parser that survives this; to_tsquery would raise a syntax
		// error and turn every clumsy query into a 500.
		for (String query : List.of("&&&", "'", "!!!", "a & | b", "\"unclosed", ":*", "-")) {
			assertNotNull(search(query), "Query '" + query + "' must not raise");
		}
	}

	@Test
	public void testBlankQueryIsRejected() {
		assertThrows(LoomRestException.class, () -> search("   "), "A blank term must be a 400, not an empty result");
	}

	@Test
	public void testOversizedQueryIsRejected() {
		assertThrows(LoomRestException.class, () -> search("x".repeat(SearchRequest.MAX_QUERY_LENGTH + 1)));
	}

	@Test
	public void testOffsetCapIsEnforced() {
		LoomRestException e = assertThrows(LoomRestException.class,
			() -> provider.search(new SearchRequest().setQuery("anything").setOffset(options.getMaxOffset() + 1)));
		assertEquals(400, e.httpCode(), "Deep paging past the cap must be a 400 naming the provider, not a timeout");
	}

	@Test
	public void testSemanticModeIsRejectedNotSilentlyDowngraded() {
		LoomRestException e = assertThrows(LoomRestException.class,
			() -> provider.search(new SearchRequest().setQuery("anything").setMode(SearchMode.SEMANTIC)));
		assertEquals(400, e.httpCode(), "An unsupported mode must be rejected; a silent fallback makes relevance bugs undiagnosable");
	}

	@Test
	public void testTypeFilter() {
		Asset asset = storeAsset("typed.mp4", "/media/typed.mp4");
		Tag tag = tagDao().createTag(adminUser(), "typed", "nature");
		tagDao().store(tag);

		SearchResult tagsOnly = provider.search(new SearchRequest().setQuery("typed").addType(SearchEntityType.TAG).setLimit(50));
		assertTrue(hits(tagsOnly, tag.getUuid()));
		assertFalse(hits(tagsOnly, asset.getUuid()), "A type filter must exclude other entity types");
	}

	@Test
	public void testTotalHitsCountsAllMatchesNotJustThePage() {
		for (int i = 0; i < 5; i++) {
			storeAsset("pagination_probe_" + i + ".mp4", "/media/p" + i + ".mp4");
		}
		SearchResult result = provider.search(new SearchRequest().setQuery("pagination_probe").setLimit(2));
		assertEquals(2, result.getHits().size(), "The page must honour the limit");
		assertTrue(result.getTotalHits() >= 5, "totalHits must count every match, not the page size");
		assertTrue(result.isTotalExact());
	}

	@Test
	public void testPagingIsStable() {
		for (int i = 0; i < 5; i++) {
			storeAsset("stable_probe_" + i + ".mp4", "/media/s" + i + ".mp4");
		}
		SearchResult first = provider.search(new SearchRequest().setQuery("stable_probe").setLimit(2).setOffset(0));
		SearchResult second = provider.search(new SearchRequest().setQuery("stable_probe").setLimit(2).setOffset(2));
		assertEquals(first.getTotalHits(), second.getTotalHits(), "totalHits must be stable across pages");
		for (SearchHit hit : second.getHits()) {
			assertFalse(hits(first, hit.getUuid()), "Pages must not overlap");
		}
	}

	@Test
	public void testHighlighting() {
		Asset asset = storeAsset("highlight.mp4", "/media/highlight.mp4");
		storeTranscript(asset, "en", "the expedition reached the summit shortly after midnight");

		SearchResult result = provider.search(new SearchRequest().setQuery("summit").setHighlight(true).setLimit(50));
		SearchHit hit = result.getHits().stream().filter(h -> asset.getUuid().equals(h.getAssetUuid())).findFirst().orElseThrow();
		assertFalse(hit.getHighlights().isEmpty(), "A highlighted search must return a snippet");
		assertNotNull(hit.getMatchedIn(), "A highlighted search must report which field matched");
	}

	@Test
	public void testSuggest() {
		Tag tag = tagDao().createTag(adminUser(), "suggestible", "nature");
		tagDao().store(tag);
		List<SearchSuggestion> suggestions = provider.suggest("sugges", Set.of(SearchEntityType.TAG), 10);
		assertTrue(suggestions.stream().anyMatch(s -> "suggestible".equals(s.getText())), "Typeahead must match a prefix");
	}

}
