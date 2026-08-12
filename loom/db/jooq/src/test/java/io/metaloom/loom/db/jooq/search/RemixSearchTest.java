package io.metaloom.loom.db.jooq.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.NoopTextEmbedder;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Remix indexing: the document sources, and every path that can make one stale.
 *
 * <p>
 * The staleness cases are the point of this class. A remix's document is built from three tables -
 * the remix row, its membership, and the filename of every member - so three different edits have to
 * reach the same document, and a missing trigger on any of them leaves the index quietly wrong
 * rather than visibly broken.
 * </p>
 */
public class RemixSearchTest extends AbstractJooqTest {

	private PostgresSearchProvider provider;

	@BeforeEach
	public void setupProvider() {
		provider = new PostgresSearchProvider(ctx(), new SearchOptions(),
			new NoopTextEmbedder("semantic search is off in this test"), new InMemoryVectorIndex());
	}

	private DSLContext ctx() {
		return context.ctx();
	}

	// --- fixtures ---------------------------------------------------------------------------------

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	private Asset storeAsset(String filename) {
		User user = adminUser();
		Asset asset = assetDao().createAsset(user, SHA512.fromString(randomSha512()), "video/mp4", filename,
			"/media/" + filename, 1024L);
		assetDao().store(asset);
		return asset;
	}

	private Remix storeRemix(String name, String description) {
		Remix remix = remixDao().createRemix(adminUser(), name);
		remix.setDescription(description);
		remixDao().store(remix);
		return remix;
	}

	private SearchResult search(String query) {
		return provider.search(new SearchRequest().setQuery(query).setLimit(50));
	}

	private SearchResult searchRemixes(String query) {
		return provider.search(new SearchRequest().setQuery(query).addType(SearchEntityType.REMIX).setLimit(50));
	}

	private boolean hits(SearchResult result, UUID uuid) {
		return result.getHits().stream().anyMatch(hit -> uuid.equals(hit.getUuid()));
	}

	// --- sources ----------------------------------------------------------------------------------

	@Test
	public void testFindsRemixByName() {
		Remix remix = storeRemix("Borealis timelapse cuts", null);
		assertTrue(hits(search("borealis"), remix.getUuid()), "A remix should be findable by its name");
	}

	@Test
	public void testFindsRemixByDescription() {
		Remix remix = storeRemix("Untitled group", "Every version of the lighthouse sequence.");
		assertTrue(hits(search("lighthouse"), remix.getUuid()), "A remix should be findable by its description");
	}

	/**
	 * The case the feature exists for: people look for a group by naming a file that is in it, not by
	 * the group's name, which they may never have chosen deliberately.
	 *
	 * <p>
	 * One token, rather than the whole filename - see {@link #testHowMuchOfAFilenameMatches()}.
	 * </p>
	 */
	@Test
	public void testFindsRemixByMemberFilename() {
		Remix remix = storeRemix("Group A", null);
		Asset asset = storeAsset("expedition7_masterclip.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.SOURCE, null, adminUser().getUuid());

		assertTrue(hits(searchRemixes("expedition7"), remix.getUuid()),
			"A remix should be findable by the filename of a member");
	}

	@Test
	public void testRemixHitCarriesItsType() {
		Remix remix = storeRemix("Kittiwake cuts", "Seabird footage.");
		SearchResult result = searchRemixes("kittiwake");

		assertFalse(result.getHits().isEmpty(), "The remix should have produced a hit");
		SearchHit hit = result.getHits().get(0);
		assertEquals(SearchEntityType.REMIX, hit.getType());
		assertEquals(remix.getUuid(), hit.getUuid());
		assertEquals("Kittiwake cuts", hit.getTitle());
	}

	/** A name match outranks a filename match, which is why the filenames go in at weight D. */
	@Test
	public void testNameOutranksMemberFilename() {
		Remix named = storeRemix("Puffin", null);
		Remix containing = storeRemix("Unrelated group", null);
		Asset asset = storeAsset("puffin_closeup.mp4");
		remixDao().linkAsset(containing.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());

		SearchResult result = searchRemixes("puffin");

		assertEquals(named.getUuid(), result.getHits().get(0).getUuid(),
			"The remix named 'Puffin' should rank above the one merely containing puffin_closeup.mp4");
	}

	/**
	 * How much of a filename you have to type. Recorded because the failing case looks like a remix
	 * bug and is not one.
	 *
	 * <p>
	 * Postgres' {@code simple} parser splits {@code razorbill_ledge.mp4} into {@code 'razorbill'} and
	 * {@code 'ledge.mp4'}, keeping the extension attached to the last segment. So a single token
	 * matches, and so does the whole filename <em>including</em> the extension, because it parses to
	 * the same two terms. What does not match is the filename with the extension dropped: that parses
	 * to {@code 'razorbill' <-> 'ledge'}, and the index holds {@code 'ledge.mp4'}.
	 * </p>
	 *
	 * <p>
	 * This is a property of the index as a whole rather than of remixes - assets behave identically,
	 * which is why {@code SearchDocumentSourceTest} only ever searches a single token.
	 * </p>
	 */
	@Test
	public void testHowMuchOfAFilenameMatches() {
		Remix remix = storeRemix("Group F", null);
		Asset asset = storeAsset("razorbill_ledge.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());

		assertTrue(hits(searchRemixes("razorbill"), remix.getUuid()), "One token matches");
		assertTrue(hits(searchRemixes("razorbill_ledge.mp4"), remix.getUuid()), "So does the whole filename");
		assertFalse(hits(searchRemixes("razorbill_ledge"), remix.getUuid()),
			"But not the filename with the extension dropped - the last token is indexed as 'ledge.mp4'");
	}

	// --- staleness --------------------------------------------------------------------------------

	@Test
	public void testRenamingARemixUpdatesTheIndex() {
		Remix remix = storeRemix("Old name guillemot", null);
		remix.setName("New name razorbill");
		remixDao().update(remix);

		assertTrue(hits(searchRemixes("razorbill"), remix.getUuid()), "The new name should be findable");
		assertFalse(hits(searchRemixes("guillemot"), remix.getUuid()), "The old name should not be");
	}

	@Test
	public void testAddingAMemberUpdatesTheIndex() {
		Remix remix = storeRemix("Group B", null);
		Asset asset = storeAsset("fulmar_glide.mp4");

		assertFalse(hits(searchRemixes("fulmar"), remix.getUuid()), "Not a member yet");

		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());

		assertTrue(hits(searchRemixes("fulmar"), remix.getUuid()), "Adding a member must reach the remix's document");
	}

	@Test
	public void testRemovingAMemberUpdatesTheIndex() {
		Remix remix = storeRemix("Group C", null);
		Asset asset = storeAsset("gannet_dive.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());
		assertTrue(hits(searchRemixes("gannet"), remix.getUuid()));

		remixDao().unlinkAsset(remix.getUuid(), asset.getUuid());

		assertFalse(hits(searchRemixes("gannet"), remix.getUuid()),
			"Removing a member must take its filename out of the remix's keywords");
	}

	/**
	 * The fan-out case. Without a trigger on the asset table, a rename leaves every remix holding
	 * that asset findable under a filename that no longer exists.
	 */
	@Test
	public void testRenamingAMemberAssetUpdatesTheIndex() {
		Remix remix = storeRemix("Group D", null);
		Asset asset = storeAsset("shearwaterold_clip.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());
		assertTrue(hits(searchRemixes("shearwaterold"), remix.getUuid()));

		asset.setFilename("shearwaternew_clip.mp4");
		assetDao().update(asset);

		assertTrue(hits(searchRemixes("shearwaternew"), remix.getUuid()), "The new filename should be findable");
		assertFalse(hits(searchRemixes("shearwaterold"), remix.getUuid()), "The old filename should not be");
	}

	@Test
	public void testDeletingARemixRemovesItsDocument() {
		Remix remix = storeRemix("Doomed skua group", null);
		assertTrue(hits(searchRemixes("skua"), remix.getUuid()));

		remixDao().delete(remix.getUuid());

		assertFalse(hits(searchRemixes("skua"), remix.getUuid()), "A deleted remix must leave no document behind");
	}

	/** Deleting a member asset cascades the membership, which must reach the remix's document too. */
	@Test
	public void testDeletingAMemberAssetUpdatesTheIndex() {
		Remix remix = storeRemix("Group E", null);
		Asset asset = storeAsset("dunlin_flock.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());
		assertTrue(hits(searchRemixes("dunlin"), remix.getUuid()));

		assetDao().delete(asset.getUuid());

		assertFalse(hits(searchRemixes("dunlin"), remix.getUuid()),
			"The cascade of remix_member must rebuild the remix's document");
	}

	// --- filtering --------------------------------------------------------------------------------

	/** The type filter is what the assets view uses to narrow to remixes. */
	@Test
	public void testTypeFilterExcludesAssets() {
		Remix remix = storeRemix("Cormorant set", null);
		Asset asset = storeAsset("cormorant_perch.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, adminUser().getUuid());

		SearchResult result = searchRemixes("cormorant");

		assertFalse(result.getHits().isEmpty(), "The remix should still be found");
		assertTrue(result.getHits().stream().allMatch(hit -> hit.getType() == SearchEntityType.REMIX),
			"types=remix must exclude the asset hit for the same term");
	}

	/** A rebuild has to produce what the triggers produced, or the two paths have drifted. */
	@Test
	public void testRebuildMatchesTheIncrementalIndex() {
		Remix remix = storeRemix("Rebuild check turnstone", "Description here.");
		Asset asset = storeAsset("turnstone_shore.mp4");
		remixDao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.SOURCE, null, adminUser().getUuid());

		String before = documentOf(remix.getUuid());
		ctx().execute("select search_document_rebuild()");
		String after = documentOf(remix.getUuid());

		assertEquals(before, after, "A full rebuild must reproduce what the triggers wrote");
	}

	private String documentOf(UUID remixUuid) {
		return ctx().fetchOne("select title || '|' || subtitle || '|' || keywords as doc"
			+ " from search_document where entity_type = 'remix' and entity_uuid = ?", remixUuid)
			.get("doc", String.class);
	}

}
