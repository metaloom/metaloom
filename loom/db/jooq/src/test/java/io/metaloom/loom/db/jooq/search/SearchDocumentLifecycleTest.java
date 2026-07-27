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
 * Trigger lifecycle guarantees of the {@code search_document} index: delete cascade, rebuild equivalence, body truncation and update refresh.
 */
public class SearchDocumentLifecycleTest extends AbstractJooqTest {

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

	// --- structural guarantees --------------------------------------------------------------------

	@Test
	public void testDeleteCascadeRemovesOnlyTheDeletedAssetsDocuments() {
		Asset doomed = storeAsset("doomed.mp4", "/media/doomed.mp4");
		Asset survivor = storeAsset("survivor.mp4", "/media/survivor.mp4");
		storeTranscript(doomed, "en", "this transcript belongs to the doomed asset");
		storeTranscript(survivor, "en", "this transcript belongs to the surviving asset");
		Tag tag = tagDao().createTag(adminUser(), "untouched", "nature");
		tagDao().store(tag);

		long before = documentCount();
		assetDao().delete(doomed.getUuid());

		assertEquals(0, documentCountForAsset(doomed.getUuid()),
			"Deleting an asset must remove its own document and its transcript document");
		assertTrue(documentCountForAsset(survivor.getUuid()) > 0, "The other asset's documents must survive");
		assertTrue(hits(search("untouched"), tag.getUuid()), "Unrelated documents must survive");
		assertTrue(documentCount() < before, "Something should actually have been removed");
	}

	@Test
	public void testRebuildEqualsIncremental() {
		// The strongest available guard against trigger drift: the triggers and the rebuild share the
		// same per-entity refresh functions, so their output must be byte-identical.
		Asset asset = storeAsset("rebuild.mp4", "/media/rebuild.mp4");
		storeTranscript(asset, "en", "a rebuild probe transcript");
		storeJsonComp(asset, "ocr", "ocr", new JsonObject().put("text", "rebuild probe ocr"));
		io.metaloom.loom.db.model.tag.AssetTag tag = tagDao().createAssetTag(adminUser(), "rebuildtag", "nature");
		tagDao().store(tag);
		tagDao().tagAsset(tag, asset);

		String columns = "entity_type, entity_uuid, asset_uuid, title, subtitle, body, keywords,"
			+ " body_truncated, lang, mime_type, size, time_from, sort_date,"
			+ " library_uuids, space_uuids, collection_uuids, tag_names";

		ctx().execute("DROP TABLE IF EXISTS search_snapshot");
		ctx().execute("CREATE TEMP TABLE search_snapshot AS SELECT " + columns + " FROM search_document");

		new NoopSearchIndexer(ctx()).rebuild();

		int drift = ctx().fetchOne("SELECT count(*) AS c FROM ("
			+ "  (SELECT " + columns + " FROM search_snapshot EXCEPT ALL SELECT " + columns + " FROM search_document)"
			+ "  UNION ALL"
			+ "  (SELECT " + columns + " FROM search_document EXCEPT ALL SELECT " + columns + " FROM search_snapshot)"
			+ ") d").get("c", Integer.class);

		assertEquals(0, drift, "A full rebuild must reproduce the incrementally maintained index exactly");
	}

	@Test
	public void testOversizedBodyIsTruncatedAndStillIndexed() {
		Asset asset = storeAsset("huge.pdf", "/media/huge.pdf");
		// A tsvector is limited to 1MB; without the trigger's cap this insert would fail outright.
		String huge = "lorem ipsum dolor sit amet ".repeat(80_000);
		storeJsonComp(asset, "tika", "tika", new JsonObject().put("content", huge));

		var record = ctx().fetchOne("SELECT length(body) AS len, body_truncated FROM search_document"
			+ " WHERE entity_type = 'asset' AND entity_uuid = ?", asset.getUuid());
		assertNotNull(record, "The document must exist despite the oversized body");
		assertTrue(record.get("body_truncated", Boolean.class), "An oversized body must be flagged as truncated");
		assertEquals(options.getBodyMaxBytes(), record.get("len", Integer.class).intValue(), "The body must be cut at the cap");
		assertTrue(hitsAsset(search("lorem"), asset.getUuid()), "A truncated document must still be searchable");
	}

	@Test
	public void testUpdateRefreshesTheDocument() {
		// The origin deliberately shares no token with either filename: initial_origin records where
		// the asset first came from and is not rewritten by a rename, so it would keep the old name
		// searchable and mask a broken update trigger.
		Asset asset = storeAsset("beforename.mp4", "/media/ingest/batch12.mp4");
		assertTrue(hits(search("beforename"), asset.getUuid()));

		asset.setFilename("aftername.mp4");
		assetDao().update(asset);

		assertTrue(hits(search("aftername"), asset.getUuid()), "An update must refresh the document");
		assertFalse(hits(search("beforename"), asset.getUuid()), "The stale filename must no longer match");
	}

	@Test
	public void testProviderReportsItsIdentityAndCapabilities() {
		assertEquals("postgres", provider.name());
		assertTrue(provider.isAvailable());
		assertTrue(provider.capabilities().contains(io.metaloom.loom.api.search.SearchCapability.LEXICAL));
		assertFalse(provider.capabilities().contains(io.metaloom.loom.api.search.SearchCapability.SEMANTIC),
			"The Postgres provider must not claim semantic search");
		assertFalse(provider.capabilities().contains(io.metaloom.loom.api.search.SearchCapability.DEEP_PAGING),
			"The Postgres provider must not claim deep paging - its offset is capped");
		assertTrue(provider.info().isAvailable());
	}


	private long documentCount() {
		return ctx().fetchOne("SELECT count(*) AS c FROM search_document").get("c", Long.class);
	}

	private long documentCountForAsset(UUID assetUuid) {
		return ctx().fetchOne("SELECT count(*) AS c FROM search_document WHERE asset_uuid = ?", assetUuid).get("c", Long.class);
	}
}
