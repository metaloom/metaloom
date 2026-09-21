package io.metaloom.loom.db.jooq.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.NoopTextEmbedder;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A transcript is indexed as timecoded windows, not as one document.
 *
 * <p>
 * Before {@code V2.110} a whole episode was a single {@code search_document} row with
 * {@code time_from = 0}. Searching for a line of dialogue therefore answered "it is somewhere in this
 * 43-minute file", which is the wrong answer to the question anybody asks a transcript. These tests
 * pin the three things that makes true: a hit carries the offset of the minute it was said in,
 * {@code assetUuid} narrows the search to one episode, and re-transcribing does not leave windows
 * behind that point at speech no longer there.
 * </p>
 */
public class TranscriptSearchWindowTest extends AbstractJooqTest {

	private PostgresSearchProvider provider;

	@BeforeEach
	public void setupProvider() {
		provider = new PostgresSearchProvider(context.ctx(), new SearchOptions(),
			new NoopTextEmbedder("semantic search is off in this test"), new InMemoryVectorIndex());
	}

	// --- fixtures ---------------------------------------------------------------------------------

	private Asset storeAsset(String filename) {
		Asset asset = assetDao().createAsset(adminUser(), SHA512.fromString(randomSha512()), "video/x-matroska", filename,
			"/content/" + filename, 4096L);
		assetDao().store(asset);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	/** A whisper transcript: {@code segments[]} with millisecond offsets, which is what the node writes. */
	private void storeWhisperTranscript(Asset asset, JsonArray segments) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < segments.size(); i++) {
			text.append(segments.getJsonObject(i).getString("text")).append(' ');
		}
		AssetTranscriptComp comp = daos().assetComponentDao().createTranscriptComp(adminUser().getUuid(), asset.getUuid(), "whisper");
		comp.setLang("en").setModel("whisper-large-v3")
			.setTranscriptText(text.toString().trim())
			.setTranscriptJson(new JsonObject().put("segments", segments));
		daos().assetComponentDao().upsertTranscriptComp(comp);
	}

	private static JsonObject segment(String text, long fromMs, long toMs) {
		return new JsonObject().put("text", text).put("from", fromMs).put("to", toMs);
	}

	private List<SearchHit> transcriptHits(String query, UUID assetUuid) {
		SearchRequest request = new SearchRequest().setQuery(query).setLimit(50).addType(SearchEntityType.TRANSCRIPT);
		if (assetUuid != null) {
			request.setAssetUuid(assetUuid);
		}
		SearchResult result = provider.search(request);
		return result.getHits();
	}

	// --- tests ------------------------------------------------------------------------------------

	@Test
	public void shouldCarryTheOffsetOfTheMinuteTheLineWasSpokenIn() {
		Asset asset = storeAsset("sg1-e01.mkv");
		storeWhisperTranscript(asset, new JsonArray()
			.add(segment("Previously on Stargate SG-1.", 2_080, 4_200))
			.add(segment("Chevron seven locked.", 61_000, 63_000))
			// Deliberately two thirds of an hour in: a zero here is indistinguishable from a
			// correct answer when every hit reports zero, which is exactly what used to happen.
			.add(segment("Close the iris.", 2_400_500, 2_402_000)));

		List<SearchHit> hits = transcriptHits("iris", asset.getUuid());
		assertEquals(1, hits.size(), "one window says 'iris', not the whole episode");
		SearchHit hit = hits.get(0);
		assertEquals(asset.getUuid(), hit.getAssetUuid());
		assertNotNull(hit.getTimeFromMs(), "a transcript hit without an offset cannot be deep-linked");
		// The window starts at the first segment inside it, which is the line itself here.
		assertEquals(2_400_500L, hit.getTimeFromMs());
	}

	@Test
	public void shouldSplitOneTranscriptIntoOneDocumentPerMinute() {
		Asset asset = storeAsset("sg1-e02.mkv");
		storeWhisperTranscript(asset, new JsonArray()
			.add(segment("alpha marker", 1_000, 2_000))
			.add(segment("alpha marker", 30_000, 31_000))
			.add(segment("alpha marker", 90_000, 91_000))
			.add(segment("alpha marker", 150_000, 151_000)));

		List<SearchHit> hits = transcriptHits("\"alpha marker\"", asset.getUuid());
		// Four utterances across three minutes: the two in the first minute share a window.
		assertEquals(3, hits.size(), "one document per minute, not per utterance and not per file");
		assertTrue(hits.stream().anyMatch(h -> h.getTimeFromMs() == 1_000L));
		assertTrue(hits.stream().anyMatch(h -> h.getTimeFromMs() == 90_000L));
		assertTrue(hits.stream().anyMatch(h -> h.getTimeFromMs() == 150_000L));
	}

	@Test
	public void shouldNarrowToOneAssetSoAViewerCanSearchInsideTheEpisodeTheyAreWatching() {
		Asset first = storeAsset("sg1-e03.mkv");
		Asset second = storeAsset("sg1-e04.mkv");
		storeWhisperTranscript(first, new JsonArray().add(segment("the stargate is buried in Egypt", 5_000, 8_000)));
		storeWhisperTranscript(second, new JsonArray().add(segment("the stargate is buried in Antarctica", 5_000, 8_000)));

		assertEquals(2, transcriptHits("stargate buried", null).size(), "both episodes say it");
		List<SearchHit> scoped = transcriptHits("stargate buried", second.getUuid());
		assertEquals(1, scoped.size(), "?asset= is what 'search inside this file' means");
		assertEquals(second.getUuid(), scoped.get(0).getAssetUuid());
	}

	@Test
	public void shouldDropWindowsThatTheNewTranscriptNoLongerHas() {
		Asset asset = storeAsset("sg1-e05.mkv");
		storeWhisperTranscript(asset, new JsonArray()
			.add(segment("shibboleth one", 1_000, 2_000))
			.add(segment("shibboleth two", 61_000, 62_000))
			.add(segment("shibboleth three", 121_000, 122_000)));
		assertEquals(3, transcriptHits("shibboleth", asset.getUuid()).size());

		// Re-run with a model that heard less. The two later windows have to go: a stale window is
		// a result that plays a moment where nobody says the thing that was searched for.
		storeWhisperTranscript(asset, new JsonArray().add(segment("shibboleth one", 1_000, 2_000)));

		List<SearchHit> hits = transcriptHits("shibboleth", asset.getUuid());
		assertEquals(1, hits.size(), "the windows the new transcript does not produce are deleted");
		assertEquals(1_000L, hits.get(0).getTimeFromMs());
	}

	// --- semantic ---------------------------------------------------------------------------------

	/**
	 * A window is embedded on its own, and a semantic hit resolves back to it rather than to the episode.
	 *
	 * <p>
	 * Both halves are new and neither works without the other. {@code SearchEmbeddingService} used to embed asset documents only — its javadoc said
	 * so — so a transcript reached the vector index as one average of forty minutes of unrelated conversation. And {@code vectorRanking} mapped
	 * every neighbour to {@code EntityKey(ASSET, assetUuid)}, so even an embedded window would have come back as "this episode", throwing away the
	 * one thing that makes a transcript hit useful.
	 * </p>
	 */
	@Test
	public void shouldFindTheMinuteSemantically() {
		// The fake model puts each topic on its own axis, so "icefall" retrieves the window that says
		// "glacier" and nothing else. A word the text does not contain is the whole point: a hit that is
		// also a lexical match proves nothing about the vector path.
		FakeTextEmbedder embedder = new FakeTextEmbedder()
			.withTopic("glacier", "icefall", "calving")
			.withTopic("saxophone", "woodwind");
		InMemoryVectorIndex index = new InMemoryVectorIndex();
		SearchOptions options = new SearchOptions().setSemanticEnabled(true)
			.setVectorType("text").setEmbedDimensions(FakeTextEmbedder.DIMENSIONS).setVectorMinScore(0.6d);
		PostgresSearchProvider semantic = new PostgresSearchProvider(context.ctx(), options, embedder, index);
		SearchEmbeddingService embeddings = new SearchEmbeddingService(context.ctx(), daos().embeddingDao(), embedder, options);

		Asset asset = storeAsset("sg1-e07.mkv");
		storeWhisperTranscript(asset, new JsonArray()
			.add(segment("nothing of interest here at all", 1_000, 5_000))
			.add(segment("the glacier front collapsed into the fjord", 600_000, 604_000))
			.add(segment("he played the saxophone badly", 1_200_000, 1_204_000)));

		int embedded = embeddings.embedStale(200);
		assertTrue(embedded >= 4, "the asset document and its three windows all need vectors, got " + embedded);
		drainIntoIndex(index);

		SearchResult result = semantic.search(new SearchRequest()
			.setQuery("icefall").setMode(SearchMode.SEMANTIC).setAssetUuid(asset.getUuid()).setLimit(20));

		List<SearchHit> windows = result.getHits().stream()
			.filter(hit -> hit.getType() == SearchEntityType.TRANSCRIPT).toList();
		assertEquals(1, windows.size(), "exactly the minute that talks about the glacier");
		assertEquals(600_000L, windows.get(0).getTimeFromMs(), "the hit has to say WHERE, or it is just an episode hit again");
		assertEquals(asset.getUuid(), windows.get(0).getAssetUuid());
	}

	/**
	 * A semantic hit carries the words it found, not just a timecode.
	 *
	 * <p>
	 * {@code ts_headline} has no term to find in a hit matched by meaning and returns an empty string, so these hits used to come back with no
	 * snippet at all. For a transcript window the client is then left rendering the title and subtitle — the filename and the window's own timecode
	 * — which is the two things already on the row. A "search by meaning" that answers with a list of timecodes and no words is not an answer.
	 * </p>
	 */
	@Test
	public void shouldGiveASemanticHitSomethingToRead() {
		FakeTextEmbedder embedder = new FakeTextEmbedder().withTopic("glacier", "icefall");
		InMemoryVectorIndex index = new InMemoryVectorIndex();
		SearchOptions options = new SearchOptions().setSemanticEnabled(true)
			.setVectorType("text").setEmbedDimensions(FakeTextEmbedder.DIMENSIONS).setVectorMinScore(0.6d);
		PostgresSearchProvider semantic = new PostgresSearchProvider(context.ctx(), options, embedder, index);
		SearchEmbeddingService embeddings = new SearchEmbeddingService(context.ctx(), daos().embeddingDao(), embedder, options);

		Asset asset = storeAsset("sg1-e08.mkv");
		storeWhisperTranscript(asset, new JsonArray()
			.add(segment("the glacier front collapsed into the fjord this morning", 300_000, 304_000)));
		embeddings.embedStale(200);
		drainIntoIndex(index);

		SearchResult result = semantic.search(new SearchRequest()
			.setQuery("icefall").setMode(SearchMode.SEMANTIC).setAssetUuid(asset.getUuid())
			.setHighlight(true).setLimit(20));

		SearchHit window = result.getHits().stream()
			.filter(hit -> hit.getType() == SearchEntityType.TRANSCRIPT).findFirst().orElseThrow();
		assertFalse(window.getHighlights().isEmpty(), "a semantic hit still needs something readable on it");
		assertTrue(window.getHighlights().get(0).contains("glacier front collapsed"),
			"and it should be the speech, not the subtitle: " + window.getHighlights());
	}

	/** Mirrors the pass {@code EmbeddingIndexSyncService} runs on a timer, which lives in a module this one cannot see. */
	private void drainIntoIndex(InMemoryVectorIndex index) {
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
	}

	@Test
	public void shouldPreferAuthoredSectionsOverTheMachineSegments() {
		Asset asset = storeAsset("sg1-e06.mkv");
		AssetTranscriptComp comp = daos().assetComponentDao().createTranscriptComp(adminUser().getUuid(), asset.getUuid(), "whisper");
		comp.setLang("en").setModel("whisper-large-v3")
			.setTranscriptText("machine text")
			.setTranscriptJson(new JsonObject()
				.put("segments", new JsonArray().add(segment("machine text", 0, 1_000)))
				// Sections are the UI's own shape and count in SECONDS, unlike segments. Somebody
				// edited these on purpose, so they win.
				.put("sections", new JsonArray().add(new JsonObject()
					.put("id", "s1")
					.put("title", "Act one")
					.put("startTime", 300.5)
					.put("endTime", 420.0)
					.put("words", new JsonArray().add(new JsonObject().put("word", "handwritten"))))));
		daos().assetComponentDao().upsertTranscriptComp(comp);

		assertEquals(0, transcriptHits("machine", asset.getUuid()).size(), "the machine segments are superseded");
		List<SearchHit> hits = transcriptHits("handwritten", asset.getUuid());
		assertEquals(1, hits.size());
		// 300.5 seconds, read as seconds and stored as milliseconds.
		assertEquals(300_500L, hits.get(0).getTimeFromMs());
	}
}
