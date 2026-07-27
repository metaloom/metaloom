package io.metaloom.loom.db.jooq.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * One test per text source that feeds a {@code search_document}.
 *
 * <p>
 * Deliberately not table driven: each source is a separate trigger path and a separate branch of
 * {@code search_extract_json_text}, so a consolidated test would report "search is broken" without saying which of the eight things broke.
 * </p>
 */
public class SearchDocumentSourceTest extends AbstractJooqTest {

	private PostgresSearchProvider provider;

	private SearchOptions options;

	@BeforeEach
	public void setupProvider() {
		options = new SearchOptions();
		provider = new PostgresSearchProvider(ctx(), options);
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

	// --- per-source coverage ----------------------------------------------------------------------

	@Test
	public void testFindsAssetByFilename() {
		Asset asset = storeAsset("borealis_timelapse.mp4", "/media/borealis_timelapse.mp4");
		assertTrue(hits(search("borealis"), asset.getUuid()), "The asset should be findable by its filename");
	}

	@Test
	public void testFindsAssetByInitialOrigin() {
		Asset asset = storeAsset("clip.mp4", "/archive/expedition7/clip.mp4");
		assertTrue(hits(search("expedition7"), asset.getUuid()), "The asset should be findable by its initial origin");
	}

	@Test
	public void testFindsAssetByTranscript() {
		Asset asset = storeAsset("interview.mp4", "/media/interview.mp4");
		storeTranscript(asset, "en", "We sailed past the lighthouse at Skagen before dawn.");
		assertTrue(hitsAsset(search("lighthouse"), asset.getUuid()), "The asset should be findable by transcript text");
	}

	@Test
	public void testTranscriptGetsItsOwnHit() {
		Asset asset = storeAsset("interview2.mp4", "/media/interview2.mp4");
		storeTranscript(asset, "en", "We sailed past the harbour at Skagen before dawn.");

		SearchResult result = provider.search(new SearchRequest().setQuery("harbour")
			.addType(SearchEntityType.TRANSCRIPT).setLimit(50));

		assertFalse(result.getHits().isEmpty(), "A transcript document should exist so a hit can deep-link into the player");
		SearchHit hit = result.getHits().get(0);
		assertEquals(SearchEntityType.TRANSCRIPT, hit.getType());
		assertEquals(asset.getUuid(), hit.getAssetUuid(), "The transcript hit must carry the asset it belongs to");
		assertNotNull(hit.getTimeFromMs(), "The transcript hit must carry a media offset");
	}

	@Test
	public void testFindsAssetByOcrText() {
		Asset asset = storeAsset("sign.jpg", "/media/sign.jpg");
		storeJsonComp(asset, "ocr", "ocr", new JsonObject().put("text", "PLATFORM NINE AND THREE QUARTERS"));
		assertTrue(hitsAsset(search("quarters"), asset.getUuid()), "OCR text lives in asset_json_comp and must be indexed");
	}

	@Test
	public void testFindsAssetByTikaContent() {
		Asset asset = storeAsset("report.pdf", "/media/report.pdf");
		storeJsonComp(asset, "tika", "tika", new JsonObject().put("content", "Quarterly revenue for the Bergen office"));
		assertTrue(hitsAsset(search("Bergen"), asset.getUuid()), "Tika content must be indexed");
	}

	@Test
	public void testFindsAssetByCaption() {
		Asset asset = storeAsset("photo.jpg", "/media/photo.jpg");
		storeJsonComp(asset, "captioning", "caption", new JsonObject().put("caption", "A red bicycle leaning on a wall"));
		assertTrue(hitsAsset(search("bicycle"), asset.getUuid()), "Image captions must be indexed");
	}

	@Test
	public void testFindsAssetBySceneCaptionInVideoCaption() {
		Asset asset = storeAsset("scenes.mp4", "/media/scenes.mp4");
		storeJsonComp(asset, "captioning", "video-caption", new JsonObject()
			.put("caption", "A journey through the fjords")
			.put("scenes", new io.vertx.core.json.JsonArray()
				.add(new JsonObject().put("caption", "a puffin on a cliff edge"))));
		assertTrue(hitsAsset(search("puffin"), asset.getUuid()), "Per-scene captions must be indexed, not only the overall caption");
	}

	@Test
	public void testFindsAssetByLlmAnswer() {
		Asset asset = storeAsset("doc.pdf", "/media/doc.pdf");
		storeJsonComp(asset, "llm", "llm", new JsonObject().put("answer", "The contract mentions an indemnity clause"));
		assertTrue(hitsAsset(search("indemnity"), asset.getUuid()), "LLM answers must be indexed");
	}

	@Test
	public void testFindsAssetByFaceDescription() {
		Asset asset = storeAsset("portrait.jpg", "/media/portrait.jpg");
		storeJsonComp(asset, "facedescription", "face-description", new JsonObject()
			.put("faces", new io.vertx.core.json.JsonArray()
				.add(new JsonObject().put("description", "a woman wearing a yellow sou'wester"))));
		assertTrue(hitsAsset(search("souwester OR yellow"), asset.getUuid()), "Face descriptions must be indexed");
	}

	@Test
	public void testQualityCompIsNotIndexed() {
		Asset asset = storeAsset("quality.mp4", "/media/quality.mp4");
		storeJsonComp(asset, "quality", "quality", new JsonObject().put("blurriness", 12.5).put("fps", 25));
		// The whitelist deliberately skips numeric payloads; indexing them would put model names and
		// enum values into the text index and wreck ranking.
		assertFalse(hitsAsset(search("blurriness"), asset.getUuid()), "Numeric quality payloads must not be indexed as text");
	}

	@Test
	public void testFindsTagByName() {
		User user = adminUser();
		Tag tag = tagDao().createTag(user, "seascape", "nature");
		tagDao().store(tag);
		assertTrue(hits(search("seascape"), tag.getUuid()), "Tags must be searchable in their own right");
	}

	@Test
	public void testFindsAssetByItsTagName() {
		Asset asset = storeAsset("tagged.mp4", "/media/tagged.mp4");
		io.metaloom.loom.db.model.tag.AssetTag tag = tagDao().createAssetTag(adminUser(), "windswept", "mood");
		tagDao().store(tag);
		tagDao().tagAsset(tag, asset);
		assertTrue(hitsAsset(search("windswept"), asset.getUuid()), "An asset must be findable by a tag attached to it");
	}

}
