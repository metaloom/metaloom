package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Tests for the DAO the REST layer reaches for whenever it needs an asset's bytes.
 *
 * <p>
 * The table is {@code asset_location}, not a content-addressed binary store, and since {@code V2.48} its key is {@code (library_uuid, path)} - an
 * asset has zero or more of them. Every non-CRUD method here exists because of that cardinality, so each is tested against an asset that genuinely
 * carries several locations rather than the single fixture row.
 * </p>
 */
public class AssetBinaryDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<AssetBinaryDao, AssetBinary> {

	/**
	 * Postgres orders {@code uuid} by comparing the 16 bytes unsigned, which is not what {@link UUID#compareTo(UUID)} does - that one compares the two
	 * halves as signed longs and disagrees with the database for any uuid whose top bit is set. The tie-break assertions have to speak the
	 * database's ordering, not Java's.
	 */
	private static final Comparator<UUID> PG_UUID_ORDER = (a, b) -> {
		int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	};

	@Override
	public AssetBinaryDao getDao() {
		return assetBinaryDao();
	}

	@Override
	public AssetBinary createElement(User user, int i) {
		// (library_uuid, path) is the natural key since V2.48, so the path has to vary with i.
		return assetBinaryDao().createAssetBinary(DUMMY_IMAGE_ORIGIN + "_" + i, ASSET_UUID, ADMIN_UUID, LIBRARY_UUID);
	}

	@Override
	public void assertCreate(AssetBinary createdElement) {
		assertEquals(LIBRARY_UUID, createdElement.getLibraryUuid());
		assertEquals(ASSET_UUID, createdElement.getAssetUuid());
		assertEquals(DUMMY_IMAGE_ORIGIN + "_0", createdElement.getPath());
	}

	@Override
	public void updateElement(AssetBinary element) {
		element.setPath("new path");
	}

	@Override
	public void assertUpdate(AssetBinary updatedElement) {
		assertEquals("new path", updatedElement.getPath());
	}

	/**
	 * The replacement for the old {@code loadByAssetUuid}, which used {@code fetchOne} and answered a second location with a
	 * {@code TooManyRowsException} - an HTTP 500 on every binary read path. "Primary" means oldest.
	 */
	@Test
	public void testLoadPrimaryByAssetUuid() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();

		assertNull(dao.loadPrimaryByAssetUuid(UUID.randomUUID()), "An unknown asset has no primary binary");

		Asset asset = storeAsset(user, 1);
		assertNull(dao.loadPrimaryByAssetUuid(asset.getUuid()), "An asset without any location has no primary binary");

		// Stored middle-first so that insertion order cannot be mistaken for creation order.
		UUID middle = storeBinary(asset.getUuid(), LIBRARY_UUID, "/pool/middle.jpg", Instant.parse("2021-06-01T00:00:00Z")).getUuid();
		UUID oldest = storeBinary(asset.getUuid(), library("archive").getUuid(), "/pool/oldest.jpg", Instant.parse("2020-01-01T00:00:00Z"))
			.getUuid();
		UUID newest = storeBinary(asset.getUuid(), library("inbox").getUuid(), "/pool/newest.jpg", Instant.parse("2022-12-24T00:00:00Z"))
			.getUuid();

		AssetBinary primary = dao.loadPrimaryByAssetUuid(asset.getUuid());
		assertNotNull(primary);
		assertEquals(oldest, primary.getUuid(), "The oldest location is the primary one");
		assertEquals("/pool/oldest.jpg", primary.getPath());
		assertEquals(asset.getUuid(), primary.getAssetUuid());

		// Same answer every time - the point of the explicit ORDER BY.
		assertEquals(oldest, dao.loadPrimaryByAssetUuid(asset.getUuid()).getUuid());
		assertEquals(oldest, dao.loadPrimaryByAssetUuid(asset.getUuid()).getUuid());

		// The other two are still there; loadPrimary limits, it does not filter.
		assertEquals(3, dao.loadAllByAssetUuid(asset.getUuid()).size());
		assertNotNull(dao.load(middle));
		assertNotNull(dao.load(newest));
	}

	/**
	 * Rows written in the same transaction share a {@code created} value, so the uuid tie-break is what actually decides the primary in practice. It
	 * has to be the database's uuid ordering, and it has to be the same on every call.
	 */
	@Test
	public void testLoadPrimaryTieBreaksOnUuid() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();
		Asset asset = storeAsset(user, 2);

		Instant sameMoment = Instant.parse("2023-03-03T03:03:03Z");
		for (int i = 0; i < 8; i++) {
			storeBinary(asset.getUuid(), library("tie_" + i).getUuid(), "/pool/tie_" + i + ".jpg", sameMoment);
		}

		UUID expected = dao.loadAllByAssetUuid(asset.getUuid()).stream()
			.map(AssetBinary::getUuid)
			.min(PG_UUID_ORDER)
			.orElseThrow();

		for (int call = 0; call < 3; call++) {
			assertEquals(expected, dao.loadPrimaryByAssetUuid(asset.getUuid()).getUuid(),
				"With equal timestamps the lowest uuid wins, on every call");
		}
	}

	/**
	 * Every location of the asset, oldest first, and nothing belonging to any other asset.
	 */
	@Test
	public void testLoadAllByAssetUuid() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();

		assertTrue(dao.loadAllByAssetUuid(UUID.randomUUID()).isEmpty(), "An unknown asset yields an empty list, not null");

		Asset asset = storeAsset(user, 3);
		Asset other = storeAsset(user, 4);

		storeBinary(asset.getUuid(), LIBRARY_UUID, "/pool/b.jpg", Instant.parse("2021-01-01T00:00:00Z"));
		storeBinary(asset.getUuid(), library("all_a").getUuid(), "/pool/a.jpg", Instant.parse("2020-01-01T00:00:00Z"));
		storeBinary(asset.getUuid(), library("all_c").getUuid(), "/pool/c.jpg", Instant.parse("2022-01-01T00:00:00Z"));
		storeBinary(other.getUuid(), library("all_other").getUuid(), "/pool/other.jpg", Instant.parse("2019-01-01T00:00:00Z"));

		List<AssetBinary> found = dao.loadAllByAssetUuid(asset.getUuid());
		assertEquals(List.of("/pool/a.jpg", "/pool/b.jpg", "/pool/c.jpg"), found.stream().map(AssetBinary::getPath).toList(),
			"All three locations of the asset, oldest first");
		assertTrue(found.stream().allMatch(b -> asset.getUuid().equals(b.getAssetUuid())));

		assertEquals(List.of("/pool/other.jpg"), dao.loadAllByAssetUuid(other.getUuid()).stream().map(AssetBinary::getPath).toList(),
			"The other asset keeps its own location and only that one");
	}

	/**
	 * The natural key of an upload: "the bytes this library holds for this asset". Two libraries holding the same asset must not see each other's
	 * row, and a library that holds nothing must yield null rather than someone else's row.
	 */
	@Test
	public void testLoadByAssetAndLibrary() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();

		Asset asset = storeAsset(user, 5);
		Asset other = storeAsset(user, 6);
		Library archive = library("lib_archive");
		Library inbox = library("lib_inbox");
		Library empty = library("lib_empty");

		UUID inArchive = storeBinary(asset.getUuid(), archive.getUuid(), "/archive/photo.jpg", null).getUuid();
		UUID inInbox = storeBinary(asset.getUuid(), inbox.getUuid(), "/inbox/photo.jpg", null).getUuid();
		storeBinary(other.getUuid(), archive.getUuid(), "/archive/other.jpg", null);

		AssetBinary fromArchive = dao.loadByAssetAndLibrary(asset.getUuid(), archive.getUuid());
		assertNotNull(fromArchive);
		assertEquals(inArchive, fromArchive.getUuid());
		assertEquals("/archive/photo.jpg", fromArchive.getPath());

		AssetBinary fromInbox = dao.loadByAssetAndLibrary(asset.getUuid(), inbox.getUuid());
		assertNotNull(fromInbox);
		assertEquals(inInbox, fromInbox.getUuid());
		assertEquals("/inbox/photo.jpg", fromInbox.getPath());

		assertNull(dao.loadByAssetAndLibrary(asset.getUuid(), empty.getUuid()), "A library holding nothing for the asset yields null");
		assertNull(dao.loadByAssetAndLibrary(UUID.randomUUID(), archive.getUuid()), "An unknown asset yields null");
		assertNull(dao.loadByAssetAndLibrary(other.getUuid(), inbox.getUuid()), "The other asset is not in the inbox");
	}

	/**
	 * The question a delete has to answer before unlinking bytes. Storage is deduplicated, so the same locator legitimately backs several rows; the
	 * pool is part of the question because the same relative key exists in every pool.
	 */
	@Test
	public void testCountByPoolAndPath() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();

		String shared = "/binaries/ab/abcdef";
		assertEquals(0, dao.countByPoolAndPath(null, shared), "Nothing points at the locator yet");

		Asset first = storeAsset(user, 7);
		Asset second = storeAsset(user, 8);

		// Two assets deduplicated onto the same bytes in the default local storage. The rows live in
		// different libraries because (library_uuid, path) is unique.
		storeBinary(first.getUuid(), library("count_a").getUuid(), shared, null);
		assertEquals(1, dao.countByPoolAndPath(null, shared));
		storeBinary(second.getUuid(), library("count_b").getUuid(), shared, null);
		assertEquals(2, dao.countByPoolAndPath(null, shared), "Both rows reference the same locator in the default local storage");

		// The same locator inside a real pool is a different object and must be counted separately.
		AssetPool pool = assetPoolDao().createAssetPool(user.getUuid(), "count-pool");
		pool.setFsPath("/tank/count-pool");
		assetPoolDao().store(pool);

		AssetBinary pooled = assetBinaryDao().createAssetBinary(shared, first.getUuid(), ADMIN_UUID, library("count_pooled").getUuid());
		pooled.setPoolUuid(pool.getUuid());
		assetBinaryDao().store(pooled);

		assertEquals(1, dao.countByPoolAndPath(pool.getUuid(), shared), "The pooled row is the only one in that pool");
		assertEquals(2, dao.countByPoolAndPath(null, shared), "The pooled row must not be counted as local storage");

		assertEquals(0, dao.countByPoolAndPath(null, "/binaries/ff/never-written"), "An unreferenced locator counts zero");
		assertEquals(0, dao.countByPoolAndPath(UUID.randomUUID(), shared), "An unknown pool counts zero");

		// Removing one row leaves the other - the whole reason the caller asks before unlinking.
		dao.delete(dao.loadAllByAssetUuid(second.getUuid()).get(0).getUuid());
		assertEquals(1, dao.countByPoolAndPath(null, shared), "The surviving row still holds the bytes");
	}

	/**
	 * Deleting an asset's binaries takes all of its locations and only its own.
	 */
	@Test
	public void testDeleteByAssetUuid() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();

		Asset asset = storeAsset(user, 9);
		Asset survivor = storeAsset(user, 10);

		storeBinary(asset.getUuid(), LIBRARY_UUID, "/pool/doomed_1.jpg", null);
		storeBinary(asset.getUuid(), library("del_a").getUuid(), "/pool/doomed_2.jpg", null);
		storeBinary(asset.getUuid(), library("del_b").getUuid(), "/pool/doomed_3.jpg", null);
		UUID keep = storeBinary(survivor.getUuid(), library("del_keep").getUuid(), "/pool/keep.jpg", null).getUuid();

		assertEquals(3, dao.loadAllByAssetUuid(asset.getUuid()).size());
		long before = dao.count();

		dao.deleteByAssetUuid(asset.getUuid());

		assertTrue(dao.loadAllByAssetUuid(asset.getUuid()).isEmpty(), "Every location of the asset is gone");
		assertNull(dao.loadPrimaryByAssetUuid(asset.getUuid()));
		assertEquals(before - 3, dao.count(), "Only the three locations of that asset were removed");
		assertNotNull(dao.load(keep), "The other asset's location survives");
		assertNotNull(dao.load(ASSET_LOCATION_UUID), "The fixture location survives");

		// Idempotent: a second delete has nothing left to remove.
		dao.deleteByAssetUuid(asset.getUuid());
		assertEquals(before - 3, dao.count());
	}

	/**
	 * A round trip through every column the table carries. {@code state}, {@code license} and {@code locked_by_uuid} were reachable only through the
	 * now-removed {@code AssetLocationDao}; a write through this DAO used to drop them silently.
	 */
	@Test
	public void testFullRoundTrip() {
		AssetBinaryDao dao = assetBinaryDao();
		User user = dummyUser();
		Asset asset = storeAsset(user, 11);

		AssetBinary binary = dao.createAssetBinary("/pool/movie.mp4", asset.getUuid(), ADMIN_UUID, library("round_trip").getUuid());
		binary.setMimeType(VIDEO_MIMETYPE);
		binary.setState("PRESENT");
		binary.setLicense("CC-BY-4.0");
		binary.setLockedByUuid(ADMIN_UUID);
		binary.setFilekeyInode(4711L);
		binary.setFilekeyStDev(66L);
		binary.setFilekeyEdate(1600000000L);
		binary.setFilekeyEdateNano(123456L);
		dao.store(binary);

		AssetBinary loaded = dao.load(binary.getUuid());
		assertNotNull(loaded);
		assertEquals("/pool/movie.mp4", loaded.getPath());
		assertEquals(VIDEO_MIMETYPE, loaded.getMimeType());
		assertEquals("PRESENT", loaded.getState());
		assertEquals("CC-BY-4.0", loaded.getLicense());
		assertEquals(ADMIN_UUID, loaded.getLockedByUuid());
		assertEquals(Long.valueOf(4711L), loaded.getFilekeyInode());
		assertEquals(Long.valueOf(66L), loaded.getFilekeyStDev());
		assertEquals(Long.valueOf(1600000000L), loaded.getFilekeyEdate());
		assertEquals(Long.valueOf(123456L), loaded.getFilekeyEdateNano());

		// An upload replacing the bytes rewrites the path, and must not blank the lock or the license
		// on its way through.
		loaded.setPath("/pool/movie-v2.mp4");
		dao.update(loaded);

		AssetBinary updated = dao.load(binary.getUuid());
		assertEquals("/pool/movie-v2.mp4", updated.getPath());
		assertEquals("PRESENT", updated.getState());
		assertEquals("CC-BY-4.0", updated.getLicense());
		assertEquals(ADMIN_UUID, updated.getLockedByUuid());
	}

	/**
	 * Build and store an asset with a primary key derived from {@code i} - {@code asset.sha512sum} is the primary key, so a fresh asset needs a fresh
	 * hash.
	 */
	private Asset storeAsset(User user, int i) {
		SHA512 sha = SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", i));
		Asset asset = assetDao().createAsset(user.getUuid(), sha, IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	private Library library(String name) {
		Library library = libraryDao().createLibrary(dummyUser(), name);
		libraryDao().store(library);
		return library;
	}

	/**
	 * Store one location. A {@code created} of null leaves the DAO's own "now", which is what production does; the ordering tests pass an explicit
	 * instant so that insertion order and creation order can be made to differ.
	 */
	private AssetBinary storeBinary(UUID assetUuid, UUID libraryUuid, String path, Instant created) {
		AssetBinary binary = assetBinaryDao().createAssetBinary(path, assetUuid, ADMIN_UUID, libraryUuid);
		if (created != null) {
			binary.setCreated(created);
		}
		assetBinaryDao().store(binary);
		return binary;
	}

}
