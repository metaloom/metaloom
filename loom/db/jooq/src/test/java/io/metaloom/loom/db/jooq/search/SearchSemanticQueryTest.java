package io.metaloom.loom.db.jooq.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.SearchCapability;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.SearchSortMode;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Semantic and hybrid ranking: the vector half of {@link PostgresSearchProvider}.
 *
 * <p>
 * The corpus is embedded through the real {@link SearchEmbeddingService} so the chain under test is the one that runs in production - search document
 * to embedding row to vector index to ranked result. Only the model itself is a stand-in ({@link FakeTextEmbedder}), because a real one would make
 * these tests depend on a downloaded model and an inference host, and would test the model's judgement rather than this code's.
 * </p>
 *
 * <p>
 * Lexical behaviour lives in {@code SearchQueryBehaviourTest}; indexing coverage in {@code SearchDocumentSourceTest}.
 * </p>
 */
public class SearchSemanticQueryTest extends AbstractJooqTest {

	private PostgresSearchProvider provider;

	private SearchEmbeddingService embeddingService;

	private SearchOptions options;

	private FakeTextEmbedder embedder;

	private InMemoryVectorIndex index;

	@BeforeEach
	public void setupProvider() {
		options = new SearchOptions().setSemanticEnabled(true);
		options.setVectorType("text").setEmbedDimensions(FakeTextEmbedder.DIMENSIONS)
			// Above the 0.5 an unrelated document scores under the index's Euclidean scoring, so "unrelated"
			// is excluded rather than merely ranked last. See InMemoryVectorIndex for the scale.
			.setVectorMinScore(0.6d);
		embedder = new FakeTextEmbedder()
			.withTopic("glacier", "icefall", "calving")
			.withTopic("bicycle", "cycling", "velocipede")
			.withTopic("saxophone", "woodwind");
		index = new InMemoryVectorIndex();
		provider = new PostgresSearchProvider(ctx(), options, embedder, index);
		embeddingService = new SearchEmbeddingService(ctx(), daos().embeddingDao(), embedder, options);
	}

	private DSLContext ctx() {
		return context.ctx();
	}

	// --- fixtures ---------------------------------------------------------------------------------

	private Asset storeAsset(String filename, String transcript) {
		User user = adminUser();
		Asset asset = assetDao().createAsset(user, SHA512.fromString(randomSha512()), "video/mp4", filename,
			"/media/" + filename, 1024L);
		assetDao().store(asset);
		AssetTranscriptComp comp = daos().assetComponentDao().createTranscriptComp(user.getUuid(), asset.getUuid(), "whisper");
		comp.setLang("en").setModel("whisper-large-v3").setTranscriptText(transcript);
		daos().assetComponentDao().upsertTranscriptComp(comp);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	/**
	 * Run the two passes that production runs on timers: embed the stale documents, then drain the new rows into the index.
	 *
	 * <p>
	 * The second half mirrors {@code EmbeddingIndexSyncService}, which lives in a module this one cannot see. Keeping it to the same three fields it
	 * copies is what keeps the mirror honest.
	 * </p>
	 */
	private int indexCorpus() {
		int embedded = embeddingService.embedStale(100);
		List<Embedding> dirty = daos().embeddingDao().findDirty(500);
		for (Embedding embedding : dirty) {
			Float[] boxed = embedding.getVector();
			float[] vector = new float[boxed.length];
			for (int i = 0; i < boxed.length; i++) {
				vector[i] = boxed[i];
			}
			index.index(new VectorRecord(embedding.getUuid(), embedding.getAssetUuid(), embedding.getDetectionUuid(),
				new VectorSpace(embedding.getType(), embedding.getModel(), vector.length), vector));
		}
		daos().embeddingDao().markSynced(dirty.stream().map(Embedding::getUuid).toList());
		return embedded;
	}

	private SearchResult search(String query, SearchMode mode) {
		return provider.search(new SearchRequest().setQuery(query).setMode(mode).setLimit(50));
	}

	private boolean hitsAsset(SearchResult result, UUID assetUuid) {
		return result.getHits().stream().anyMatch(hit -> assetUuid.equals(hit.getAssetUuid()));
	}

	// --- capability gating ------------------------------------------------------------------------

	@Test
	public void testCapabilitiesAreAdvertisedOnlyWhenBothHalvesWork() {
		assertTrue(provider.capabilities().contains(SearchCapability.SEMANTIC));
		assertTrue(provider.capabilities().contains(SearchCapability.HYBRID));

		// Each of the three ways semantic can be unavailable must retract the capability, because the UI
		// renders its mode toggle from exactly this set.
		options.setSemanticEnabled(false);
		assertFalse(provider.capabilities().contains(SearchCapability.SEMANTIC), "Disabling must retract the capability");

		options.setSemanticEnabled(true);
		embedder.unavailable();
		assertFalse(provider.capabilities().contains(SearchCapability.SEMANTIC), "An unreachable embedding host must retract the capability");

		provider = new PostgresSearchProvider(ctx(), options, new FakeTextEmbedder(), new InMemoryVectorIndex().unavailable());
		assertFalse(provider.capabilities().contains(SearchCapability.SEMANTIC), "An unavailable vector index must retract the capability");
	}

	@Test
	public void testSemanticIsRejectedRatherThanAnsweredLexicallyWhenOff() {
		options.setSemanticEnabled(false);
		LoomRestException e = assertThrows(LoomRestException.class, () -> search("glacier", SearchMode.SEMANTIC));
		assertEquals(400, e.httpCode());
		// The reason has to name the switch, or the operator has three things to check and no hint which.
		assertTrue(e.getMessage().contains("LOOM_SEARCH_SEMANTIC_ENABLED"), "Expected the reason to name the option, got: " + e.getMessage());
	}

	@Test
	public void testLexicalStillWorksWithSemanticEnabled() {
		Asset asset = storeAsset("lex.mp4", "a glacier at sunrise");
		SearchResult result = provider.search(new SearchRequest().setQuery("glacier").setLimit(50));
		assertTrue(hitsAsset(result, asset.getUuid()));
		assertTrue(result.isTotalExact(), "The lexical path must keep its exact totals");
	}

	// --- semantic ranking -------------------------------------------------------------------------

	@Test
	public void testSemanticFindsAnAssetByMeaningRatherThanByWord() {
		Asset glacier = storeAsset("one.mp4", "a glacier at sunrise");
		Asset bicycle = storeAsset("two.mp4", "a bicycle leaning on a wall");
		indexCorpus();

		SearchResult result = search("glacier", SearchMode.SEMANTIC);
		assertTrue(hitsAsset(result, glacier.getUuid()), "The matching asset must rank");
		assertFalse(hitsAsset(result, bicycle.getUuid()), "An unrelated asset must fall below the score floor");
	}

	@Test
	public void testSemanticFindsADocumentThroughAWordItDoesNotContain() {
		// The reason this feature exists. Nothing in this asset's text is the word the user typed, so no
		// amount of lexical cleverness reaches it - and the lexical assertion below is what proves that.
		Asset asset = storeAsset("alps.mp4", "a long cycling tour through the alps");
		indexCorpus();

		assertTrue(hitsAsset(search("bicycle", SearchMode.SEMANTIC), asset.getUuid()), "Semantic search must reach it");
		assertFalse(hitsAsset(search("bicycle", SearchMode.LEXICAL), asset.getUuid()), "Lexical search must not - it shares no word");
	}

	@Test
	public void testSemanticHitsAreLabelledAsSuchRatherThanAsFuzzy() {
		storeAsset("one.mp4", "a glacier at sunrise");
		indexCorpus();

		SearchResult result = search("glacier", SearchMode.SEMANTIC);
		assertEquals(PostgresSearchProvider.MATCHED_IN_SEMANTIC, result.getHits().get(0).getMatchedIn(),
			"A hit no lexical clause matched must not be reported as a fuzzy text match");
	}

	@Test
	public void testSemanticTotalsAreReportedAsInexact() {
		storeAsset("one.mp4", "a glacier at sunrise");
		indexCorpus();
		// Both rankers are capped at topK, so an exact total would be a number the ranking cannot honour.
		assertFalse(search("glacier", SearchMode.SEMANTIC).isTotalExact());
	}

	@Test
	public void testAQueryAboutNothingInTheCorpusReturnsNothing() {
		Asset asset = storeAsset("one.mp4", "a glacier at sunrise");
		indexCorpus();
		assertFalse(hitsAsset(search("saxophone", SearchMode.SEMANTIC), asset.getUuid()));
	}

	@Test
	public void testAnUnembeddedCorpusReturnsNothingRatherThanFailing() {
		Asset asset = storeAsset("one.mp4", "a glacier at sunrise");
		// Deliberately no indexCorpus(): a deployment that just turned semantic search on has an empty
		// index for a while, and that has to look like "no matches", not like an error.
		assertFalse(hitsAsset(search("glacier", SearchMode.SEMANTIC), asset.getUuid()));
	}

	// --- hybrid -----------------------------------------------------------------------------------

	@Test
	public void testHybridReturnsWhatEitherRankerFinds() {
		Asset semantic = storeAsset("one.mp4", "a glacier at sunrise");
		Asset lexicalOnly = storeAsset("saxophone-solo.mp4", "an unrelated recording");
		indexCorpus();

		SearchResult result = search("glacier", SearchMode.HYBRID);
		assertTrue(hitsAsset(result, semantic.getUuid()), "The vector ranker's find must survive fusion");

		SearchResult byFilename = search("saxophone-solo", SearchMode.HYBRID);
		assertTrue(hitsAsset(byFilename, lexicalOnly.getUuid()), "The lexical ranker's find must survive fusion too");
	}

	@Test
	public void testHybridRanksTheDocumentBothRankersAgreeOnFirst() {
		Asset both = storeAsset("bicycle-repair.mp4", "fixing a bicycle in the yard");
		Asset lexicalOnly = storeAsset("bicycle-invoice.txt", "a saxophone was purchased");
		Asset semanticOnly = storeAsset("alps.mp4", "a long cycling tour through the alps");
		indexCorpus();

		SearchResult result = search("bicycle", SearchMode.HYBRID);
		assertEquals(both.getUuid(), result.getHits().get(0).getAssetUuid(),
			"The asset both rankers rank must come first - that agreement is the point of fusing");
		assertTrue(hitsAsset(result, lexicalOnly.getUuid()), "The lexical-only candidate must still appear");
		assertTrue(hitsAsset(result, semanticOnly.getUuid()), "The semantic-only candidate must still appear");
	}

	@Test
	public void testHybridStillAnswersWhenTheIndexIsEmpty() {
		Asset asset = storeAsset("glacier.mp4", "a glacier at sunrise");
		// No indexCorpus(): the vector ranker contributes nothing and fusion must degrade to the lexical
		// order rather than to an empty page.
		assertTrue(hitsAsset(search("glacier", SearchMode.HYBRID), asset.getUuid()));
	}

	// --- filters and paging -----------------------------------------------------------------------

	@Test
	public void testFiltersApplyToVectorCandidatesToo() {
		Asset asset = storeAsset("one.mp4", "a glacier at sunrise");
		indexCorpus();

		SearchRequest matching = new SearchRequest().setQuery("glacier").setMode(SearchMode.SEMANTIC).setMimeTypePrefix("video/");
		assertTrue(hitsAsset(provider.search(matching), asset.getUuid()));

		// The vector index knows nothing about mime types, so a filter that excludes the candidate has to
		// be applied after fusion or it would be silently ignored.
		SearchRequest excluding = new SearchRequest().setQuery("glacier").setMode(SearchMode.SEMANTIC).setMimeTypePrefix("image/");
		assertFalse(hitsAsset(provider.search(excluding), asset.getUuid()), "A filter must exclude a vector candidate");
	}

	@Test
	public void testSortIsHonouredRatherThanSilentlyIgnored() {
		// The UI shows its sort control in every mode. Fusion produces its own order, so unless this is
		// applied the control is a visible widget that does nothing.
		storeAsset("b-glacier.mp4", "a glacier at sunrise");
		storeAsset("a-glacier.mp4", "a glacier at noon");
		storeAsset("c-glacier.mp4", "a glacier at dusk");
		indexCorpus();

		SearchResult byName = provider.search(new SearchRequest().setQuery("glacier").setMode(SearchMode.SEMANTIC)
			.setSort(SearchSortMode.NAME).setLimit(50));
		List<String> titles = byName.getHits().stream().map(hit -> hit.getTitle()).toList();
		assertEquals(titles.stream().sorted().toList(), titles, "Hits must come back in name order");
	}

	@Test
	public void testSemanticPagingDoesNotRepeatOrSkipAHit() {
		for (int i = 0; i < 5; i++) {
			storeAsset("clip" + i + ".mp4", "a glacier at sunrise, take " + i);
		}
		indexCorpus();

		SearchResult first = provider.search(new SearchRequest().setQuery("glacier").setMode(SearchMode.SEMANTIC).setLimit(2).setOffset(0));
		SearchResult second = provider.search(new SearchRequest().setQuery("glacier").setMode(SearchMode.SEMANTIC).setLimit(2).setOffset(2));
		assertEquals(2, first.getHits().size());
		assertEquals(2, second.getHits().size());

		List<UUID> firstPage = first.getHits().stream().map(hit -> hit.getUuid()).toList();
		assertTrue(second.getHits().stream().noneMatch(hit -> firstPage.contains(hit.getUuid())),
			"A stable tie-break is what keeps a document off two pages at once");
	}

	// --- failure modes ----------------------------------------------------------------------------

	@Test
	public void testAFailingEmbeddingHostIsAnOutageNotAnEmptyResult() {
		storeAsset("one.mp4", "a glacier at sunrise");
		indexCorpus();
		embedder.failing(new IllegalStateException("connection refused"));

		// "The ranker is down" and "nothing matched" are opposite answers and must not look the same.
		LoomRestException e = assertThrows(LoomRestException.class, () -> search("glacier", SearchMode.SEMANTIC));
		assertEquals(503, e.httpCode());
	}

	// --- the embedding pass -----------------------------------------------------------------------

	@Test
	public void testTheEmbeddingPassIsIncrementalRatherThanRepeating() {
		// Counts are relative: the pooled test database carries fixtures of its own, so the number that
		// matters is how the backlog moves, not what it starts at.
		storeAsset("one.mp4", "a glacier at sunrise");
		assertTrue(embeddingService.pendingCount() >= 1, "A new document must be pending");
		assertTrue(embeddingService.embedStale(500) >= 1, "The new document must be embedded");
		assertEquals(0, embeddingService.embedStale(500), "An unchanged corpus must not be embedded again");
		assertEquals(0, embeddingService.pendingCount(), "Nothing may remain pending after a full pass");
	}

	@Test
	public void testChangedContentIsReEmbedded() {
		Asset asset = storeAsset("one.mp4", "a glacier at sunrise");
		embeddingService.embedStale(500);
		assertEquals(0, embeddingService.pendingCount());

		AssetTranscriptComp comp = daos().assetComponentDao().createTranscriptComp(adminUser().getUuid(), asset.getUuid(), "whisper");
		comp.setLang("en").setModel("whisper-large-v3").setTranscriptText("a bicycle leaning on a wall");
		daos().assetComponentDao().upsertTranscriptComp(comp);

		assertEquals(1, embeddingService.pendingCount(), "A refreshed document must go stale again");
		assertEquals(1, embeddingService.embedStale(500));
	}

	@Test
	public void testANewModelWritesBesideTheOldOneRatherThanOverIt() {
		Asset asset = storeAsset("one.mp4", "a glacier at sunrise");
		embeddingService.embedStale(500);

		// model is part of the embedding identity key, so a model upgrade is reversible: both vector sets
		// exist at once and the old one is dropped only once the new one has been shown to be better.
		SearchEmbeddingService upgraded = new SearchEmbeddingService(ctx(), daos().embeddingDao(),
			new FakeTextEmbedder("fake-embed-v2").withTopic("glacier"), options);
		assertTrue(upgraded.embedStale(500) >= 1, "The new model must re-embed the corpus");

		long stored = daos().embeddingDao().streamAll().filter(e -> asset.getUuid().equals(e.getAssetUuid())).count();
		assertEquals(2, stored, "Both models' vectors must coexist for the same asset");
	}

	@Test
	public void testTheEmbeddingPassIsInertWhenSemanticIsOff() {
		storeAsset("one.mp4", "a glacier at sunrise");
		options.setSemanticEnabled(false);
		assertFalse(embeddingService.isReady());
		assertEquals(0, embeddingService.embedStale(500), "Nothing may be embedded while semantic search is off");
	}
}
