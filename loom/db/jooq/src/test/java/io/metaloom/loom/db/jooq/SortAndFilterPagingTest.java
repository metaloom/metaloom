package io.metaloom.loom.db.jooq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;

/**
 * Keyset paging over a caller-chosen sort column, and the filters that narrow it.
 *
 * <p>
 * Sorting used to be a half-feature: {@code ?sort=name} produced the right {@code ORDER BY}, so a first page looked correct, and every page after it
 * was a 500 — {@code getField} coerced the sort column to {@code Field<UUID>}, which only becomes visible once a cursor turns the column into a
 * {@code WHERE} predicate. These tests therefore always page past the first page; a single-page assertion would have passed against the old code.
 * </p>
 */
public class SortAndFilterPagingTest extends AbstractJooqTest {

	/** Page size small enough that every fixture below spans several pages. */
	private static final int PAGE_SIZE = 7;

	// ── Helpers ───────────────────────────────────────────────────────────

	/**
	 * Page through the collections, keeping only the rows this test created.
	 *
	 * <p>
	 * The pooled database arrives pre-populated, so absolute assertions about a listing are meaningless here (see {@code jooq-test-db-prepopulated}).
	 * Every fixture gets a run-unique prefix and everything else is discarded.
	 * </p>
	 */
	private List<String> pageCollectionNames(String prefix, SortKey sortBy, SortDirection direction, List<Filter> filters) {
		return pageAll(prefix, sortBy, direction, filters, Collection::getName,
			(cursor) -> collectionDao().loadPage(cursor, PAGE_SIZE, filters, sortBy, direction));
	}

	private <T extends io.metaloom.loom.db.Element<T>> List<String> pageAll(String prefix, SortKey sortBy, SortDirection direction,
		List<Filter> filters, Function<T, String> label, Function<UUID, Page<T>> loader) {
		List<String> collected = new ArrayList<>();
		List<UUID> seen = new ArrayList<>();
		UUID cursor = null;
		// Bounded so a paging bug fails as an assertion rather than as a hung build.
		for (int guard = 0; guard < 200; guard++) {
			Page<T> page = loader.apply(cursor);
			if (page.isEmpty()) {
				return collected;
			}
			for (T element : page) {
				assertThat(seen)
					.as("keyset paging must not return the same row twice")
					.doesNotContain(element.getUuid());
				seen.add(element.getUuid());
				String value = label.apply(element);
				if (value != null && value.startsWith(prefix)) {
					collected.add(value);
				}
			}
			cursor = page.last().getUuid();
		}
		throw new AssertionError("Paging did not terminate - the cursor is not advancing");
	}

	private String uniquePrefix(String label) {
		return label + "_" + UUID.randomUUID().toString().substring(0, 8) + "_";
	}

	private List<String> createCollections(User user, String prefix, List<String> suffixes) {
		List<String> names = new ArrayList<>();
		for (String suffix : suffixes) {
			Collection collection = collectionDao().createCollection(user, prefix + suffix);
			collectionDao().store(collection);
			names.add(collection.getName());
		}
		return names;
	}

	// ── Sorting ───────────────────────────────────────────────────────────

	/**
	 * The headline case: order by a text column and walk every page.
	 *
	 * <p>
	 * Names are created in an order that is neither alphabetical nor reverse alphabetical, so a result that happens to match insertion order would
	 * still fail.
	 * </p>
	 */
	@Test
	public void testSortByNameAscendingAcrossPages() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_name");

		List<String> suffixes = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			suffixes.add(String.format("%03d", i));
		}
		Collections.shuffle(suffixes, new java.util.Random(4711));
		List<String> names = createCollections(user, prefix, suffixes);

		List<String> expected = new ArrayList<>(names);
		Collections.sort(expected);

		assertThat(pageCollectionNames(prefix, LoomSortKey.NAME, SortDirection.ASCENDING, null))
			.as("every page of a name-sorted listing, concatenated")
			.containsExactlyElementsOf(expected);
	}

	@Test
	public void testSortByNameDescendingAcrossPages() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_name_desc");

		List<String> suffixes = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			suffixes.add(String.format("%03d", i));
		}
		Collections.shuffle(suffixes, new java.util.Random(1234));
		List<String> names = createCollections(user, prefix, suffixes);

		List<String> expected = new ArrayList<>(names);
		expected.sort(Comparator.reverseOrder());

		assertThat(pageCollectionNames(prefix, LoomSortKey.NAME, SortDirection.DESCENDING, null))
			.containsExactlyElementsOf(expected);
	}

	/**
	 * A sort column that is not unique.
	 *
	 * <p>
	 * This is the case a compound-free seek loses rows on: with twenty rows sharing one name, {@code WHERE name > 'same'} skips the entire group the
	 * moment the cursor lands inside it. The uuid tiebreaker appended to every ORDER BY is what makes the page boundary exact, so the assertion that
	 * matters here is the count, not the order.
	 * </p>
	 *
	 * <p>
	 * Assets rather than collections: {@code collection.name} carries a unique index, so the collision cannot be built there.
	 * </p>
	 */
	@Test
	public void testDuplicateSortValuesPageWithoutLoss() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_dup");
		String shared = prefix + "same.jpg";

		for (int i = 0; i < 20; i++) {
			// Deliberately identical filenames: every row collides on the sort column. The natural
			// key is sha512sum, which storeAsset keeps unique, so this is a legal state.
			storeAsset(user, shared);
		}

		List<String> paged = pageAll(prefix, LoomSortKey.NAME, SortDirection.ASCENDING, null, Asset::getFilename,
			cursor -> assetDao().loadPage(cursor, PAGE_SIZE, null, LoomSortKey.NAME, SortDirection.ASCENDING));

		assertThat(paged)
			.as("20 rows sharing one name, paged " + PAGE_SIZE + " at a time")
			.hasSize(20)
			.containsOnly(shared);
	}

	/**
	 * Sorting by the creation timestamp.
	 *
	 * <p>
	 * The rows are given explicit, deliberately out-of-order timestamps rather than being created in sequence — inserting them in order would make
	 * the result indistinguishable from the default uuid ordering, which is already time ordered under UUIDv7.
	 * </p>
	 */
	@Test
	public void testSortByCreated() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_created");

		Instant base = Instant.parse("2026-03-01T00:00:00Z");
		// Insertion order 0,1,2..; creation instants run the other way.
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Collection collection = collectionDao().createCollection(user, prefix + String.format("%03d", i));
			collection.setCreated(base.minus(Duration.ofHours(i)));
			collectionDao().store(collection);
			expected.add(collection.getName());
		}
		// Oldest first, so the reverse of the order they were inserted in.
		Collections.reverse(expected);

		assertThat(pageCollectionNames(prefix, LoomSortKey.CREATED, SortDirection.ASCENDING, null))
			.containsExactlyElementsOf(expected);
	}

	/** Same again for the edit timestamp, which is the "recently touched" ordering. */
	@Test
	public void testSortByEditedDescending() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_edited");

		Instant base = Instant.parse("2026-03-01T00:00:00Z");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Collection collection = collectionDao().createCollection(user, prefix + String.format("%03d", i));
			collection.setEdited(base.plus(Duration.ofHours(i)));
			collectionDao().store(collection);
			expected.add(collection.getName());
		}
		// Most recently edited first.
		Collections.reverse(expected);

		assertThat(pageCollectionNames(prefix, LoomSortKey.EDITED, SortDirection.DESCENDING, null))
			.containsExactlyElementsOf(expected);
	}

	/**
	 * An asset's display name is {@code filename}; the table has no {@code name} column at all.
	 *
	 * <p>
	 * Without the per-type mapping this is a 400 rather than a listing, which would make the UI's single sort control wrong on the one view that
	 * matters most.
	 * </p>
	 */
	@Test
	public void testSortAssetsByNameUsesFilename() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_asset");

		List<String> suffixes = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			suffixes.add(String.format("%03d", i));
		}
		Collections.shuffle(suffixes, new java.util.Random(99));
		List<String> expected = new ArrayList<>();
		for (String suffix : suffixes) {
			Asset asset = storeAsset(user, prefix + suffix + ".jpg");
			expected.add(asset.getFilename());
		}
		Collections.sort(expected);

		List<String> paged = pageAll(prefix, LoomSortKey.NAME, SortDirection.ASCENDING, null, Asset::getFilename,
			cursor -> assetDao().loadPage(cursor, PAGE_SIZE, null, LoomSortKey.NAME, SortDirection.ASCENDING));

		assertThat(paged).containsExactlyElementsOf(expected);
	}

	/** A column this type does not have is the caller's mistake, so 400 rather than 500. */
	@Test
	public void testUnknownSortFieldIsRejected() {
		assertThatThrownBy(() -> collectionDao().loadPage(null, PAGE_SIZE, null, LoomSortKey.SHA512, SortDirection.ASCENDING))
			.isInstanceOf(LoomRestException.class)
			.hasMessageContaining("Unknown sort field sha512 for Collections");
	}

	/**
	 * A cursor pointing at a row that has since been deleted.
	 *
	 * <p>
	 * Resuming a sorted page needs that row's sort value, and there is no longer one to read. Answering 400 is the point: silently seeking from
	 * nothing would restart at page one, and a client looping until the page comes back empty would then never terminate.
	 * </p>
	 */
	@Test
	public void testDeletedCursorIsRejectedRatherThanRestarting() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_gone");
		createCollections(user, prefix, List.of("a", "b", "c"));

		Collection doomed = collectionDao().createCollection(user, prefix + "doomed");
		collectionDao().store(doomed);
		UUID cursor = doomed.getUuid();
		collectionDao().delete(cursor);

		assertThatThrownBy(() -> collectionDao().loadPage(cursor, PAGE_SIZE, null, LoomSortKey.NAME, SortDirection.ASCENDING))
			.isInstanceOf(LoomRestException.class)
			.hasMessageContaining("Cannot resume a sorted page");
	}

	/**
	 * The unsorted default still seeks on the uuid alone and does not need the cursor row to exist.
	 *
	 * <p>
	 * Guards against the compound seek being applied unconditionally: uuid ordering can resume from a deleted uuid perfectly well, and taking that
	 * away would be a regression in the path every existing client uses.
	 * </p>
	 */
	@Test
	public void testDefaultOrderStillResumesFromADeletedCursor() {
		User user = dummyUser();
		String prefix = uniquePrefix("sort_default");
		createCollections(user, prefix, List.of("a", "b", "c"));

		Collection doomed = collectionDao().createCollection(user, prefix + "doomed");
		collectionDao().store(doomed);
		UUID cursor = doomed.getUuid();
		collectionDao().delete(cursor);

		Page<Collection> page = collectionDao().loadPage(cursor, PAGE_SIZE, null, null, null);
		assertThat(page).as("an unsorted listing resumes from a vanished cursor without complaint").isNotNull();
	}

	// ── Filtering ─────────────────────────────────────────────────────────

	/**
	 * Filter collections by the user that created them.
	 *
	 * <p>
	 * Handled by the base DAO off the {@code creator_uuid} audit column, so this covers every type carrying it, not only collections.
	 * </p>
	 */
	@Test
	public void testFilterCollectionsByCreator() {
		User mine = dummyUser();
		User theirs = adminUser();
		String prefix = uniquePrefix("filter_creator");

		List<String> expected = createCollections(mine, prefix, List.of("mine-1", "mine-2", "mine-3"));
		createCollections(theirs, prefix, List.of("theirs-1", "theirs-2"));

		List<Filter> filters = List.of(LoomFilterKey.CREATOR.eq(mine.getUuid().toString()));
		List<String> paged = pageAll(prefix, null, null, filters, Collection::getName,
			cursor -> collectionDao().loadPage(cursor, PAGE_SIZE, filters, null, null));

		assertThat(paged)
			.as("only the collections created by the filtered user")
			.containsExactlyInAnyOrderElementsOf(expected);
	}

	/** Creator filtering has to compose with sorting — they are independent clauses, and the UI sets both at once. */
	@Test
	public void testFilterByCreatorCombinedWithSort() {
		User mine = dummyUser();
		User theirs = adminUser();
		String prefix = uniquePrefix("filter_sort");

		List<String> expected = createCollections(mine, prefix, List.of("delta", "alpha", "charlie", "bravo"));
		createCollections(theirs, prefix, List.of("zulu", "yankee"));
		Collections.sort(expected);

		List<Filter> filters = List.of(LoomFilterKey.CREATOR.eq(mine.getUuid().toString()));
		assertThat(pageCollectionNames(prefix, LoomSortKey.NAME, SortDirection.ASCENDING, filters))
			.containsExactlyElementsOf(expected);
	}

	/**
	 * Filter assets by collection membership.
	 *
	 * <p>
	 * Membership is a join table, so the assertion that matters beyond "the right rows" is the reported total: an inner join would multiply an asset
	 * by its memberships and make {@code totalCount} report links rather than assets.
	 * </p>
	 */
	@Test
	public void testFilterAssetsByCollection() {
		User user = dummyUser();
		String prefix = uniquePrefix("filter_coll");

		Collection collection = collectionDao().createCollection(user, prefix + "target");
		collectionDao().store(collection);
		Collection other = collectionDao().createCollection(user, prefix + "other");
		collectionDao().store(other);

		// Zero-padded so that Java's ordering and Postgres' collation agree. Unpadded, "in-1.jpg"
		// and "in-10.jpg" order differently in the two: the database's en_US collation ignores the
		// punctuation and then ranks the digit before the 'j' of ".jpg".
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Asset asset = storeAsset(user, prefix + String.format("in-%02d", i) + ".jpg");
			collectionDao().linkAsset(collection.getUuid(), asset.getUuid());
			expected.add(asset.getFilename());
			// A second membership for some of them, so a join-based implementation double counts.
			if (i % 3 == 0) {
				collectionDao().linkAsset(other.getUuid(), asset.getUuid());
			}
		}
		for (int i = 0; i < 5; i++) {
			Asset asset = storeAsset(user, prefix + String.format("out-%02d", i) + ".jpg");
			collectionDao().linkAsset(other.getUuid(), asset.getUuid());
		}
		Collections.sort(expected);

		List<Filter> filters = List.of(LoomFilterKey.COLLECTION.eq(collection.getUuid().toString()));
		List<String> paged = pageAll(prefix, LoomSortKey.NAME, SortDirection.ASCENDING, filters, Asset::getFilename,
			cursor -> assetDao().loadPage(cursor, PAGE_SIZE, filters, LoomSortKey.NAME, SortDirection.ASCENDING));

		assertThat(paged)
			.as("only the assets linked to the filtered collection")
			.containsExactlyElementsOf(expected);

		Page<Asset> firstPage = assetDao().loadPage(null, PAGE_SIZE, filters, LoomSortKey.NAME, SortDirection.ASCENDING);
		assertThat(firstPage.totalCount())
			.as("total counts assets, not collection_asset links - four of the twelve are in two collections")
			.isEqualTo(12);
	}

	/** A filter key the type does not implement is a 400 naming the type, not a silently ignored parameter. */
	@Test
	public void testUnknownFilterKeyIsRejected() {
		List<Filter> filters = List.of(LoomFilterKey.STATUS.eq("SUCCESS"));
		assertThatThrownBy(() -> collectionDao().loadPage(null, PAGE_SIZE, filters, null, null))
			.isInstanceOf(LoomRestException.class)
			.hasMessageContaining("Unknown filter field status for Collections");
	}

	/** A creator filter whose value is not a uuid is the caller's mistake, so 400 rather than the 500 an unguarded parse produced. */
	@Test
	public void testMalformedUuidFilterValueIsRejected() {
		List<Filter> filters = List.of(LoomFilterKey.CREATOR.eq("not-a-uuid"));
		assertThatThrownBy(() -> collectionDao().loadPage(null, PAGE_SIZE, filters, null, null))
			.isInstanceOf(LoomRestException.class)
			.hasMessageContaining("expects a uuid");
	}

	// ── Fixtures ──────────────────────────────────────────────────────────

	/**
	 * A stored asset with a hash unique to this run.
	 *
	 * <p>
	 * {@code sha512sum} is the asset's natural key and is unique, so fixtures cannot share one. Four uuids' worth of hex is 128 characters, which is
	 * the length the hash type expects.
	 * </p>
	 */
	private Asset storeAsset(User user, String filename) {
		StringBuilder hex = new StringBuilder(128);
		while (hex.length() < 128) {
			hex.append(UUID.randomUUID().toString().replace("-", ""));
		}
		Asset asset = assetDao().createAsset(user.getUuid(), SHA512.fromString(hex.substring(0, 128)), "image/jpeg", filename,
			"/fixtures/" + filename, 1024L);
		assetDao().store(asset);
		return asset;
	}
}
