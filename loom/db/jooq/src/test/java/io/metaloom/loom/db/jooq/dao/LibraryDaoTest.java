package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;

public class LibraryDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<LibraryDao, Library> {

	@Override
	public Library createElement(User user, int i) {
		return libraryDao().createLibrary(user, "library_" + i);
	}

	@Override
	public void assertCreate(Library createdElement) {
		assertEquals("library_0", createdElement.getName());
	}

	@Override
	public LibraryDao getDao() {
		return libraryDao();
	}

	@Override
	public void updateElement(Library element) {
		element.setName("Dummy2");
	}

	@Override
	public void assertUpdate(Library updatedLibrary) {
		assertEquals("Dummy2", updatedLibrary.getName());
	}

	/**
	 * {@code library_asset} gained its first DAO writer with the membership routes. Before that every caller - including this test suite - inserted
	 * the row with raw jOOQ, which is why the idempotency this asserts had nowhere to live.
	 */
	@Test
	public void testLinkAssetIsIdempotent() {
		User user = dummyUser();
		Library library = getDao().createLibrary(user, "idempotent_link");
		getDao().store(library);
		Asset asset = dummyAsset(user);

		getDao().linkAsset(library.getUuid(), asset.getUuid());
		getDao().linkAsset(library.getUuid(), asset.getUuid());

		assertTrue(getDao().containsAsset(library.getUuid(), asset.getUuid()), "The asset should be a member");
		assertEquals(1, getDao().countAssets(library.getUuid()), "The asset should be a member exactly once");
	}

	@Test
	public void testUnlinkAsset() {
		User user = dummyUser();
		Library library = getDao().createLibrary(user, "unlink");
		getDao().store(library);
		Asset asset = dummyAsset(user);

		getDao().linkAsset(library.getUuid(), asset.getUuid());
		getDao().unlinkAsset(library.getUuid(), asset.getUuid());

		assertFalse(getDao().containsAsset(library.getUuid(), asset.getUuid()), "The asset should no longer be a member");
		assertEquals(0, getDao().countAssets(library.getUuid()), "The library should have no members");
	}

	@Test
	public void testLoadPageByAsset() {
		User user = dummyUser();
		Asset asset = dummyAsset(user);

		Library first = getDao().createLibrary(user, "page_by_asset_a");
		getDao().store(first);
		Library second = getDao().createLibrary(user, "page_by_asset_b");
		getDao().store(second);
		getDao().linkAsset(first.getUuid(), asset.getUuid());
		getDao().linkAsset(second.getUuid(), asset.getUuid());

		Page<Library> page = getDao().loadPageByAsset(asset.getUuid(), null, 25);
		assertEquals(2, page.totalCount(), "Both libraries should be reachable from the asset");
	}

	/**
	 * Deleting an asset takes its library memberships with it (V2.74) and leaves every other member of that library alone.
	 */
	@Test
	public void testDeletingAnAssetLeavesTheOtherMembers() {
		User user = dummyUser();
		Library library = getDao().createLibrary(user, "cascade_library");
		getDao().store(library);

		Asset victim = dummyAsset(user);
		Asset bystander = dummyAsset(user);
		getDao().linkAsset(library.getUuid(), victim.getUuid());
		getDao().linkAsset(library.getUuid(), bystander.getUuid());

		assetDao().delete(victim.getUuid());

		assertFalse(getDao().containsAsset(library.getUuid(), victim.getUuid()), "The deleted asset's membership must have cascaded");
		assertTrue(getDao().containsAsset(library.getUuid(), bystander.getUuid()), "The other asset must stay in the library");
		assertNotNull(getDao().load(library.getUuid()), "The library itself must survive");
	}

	private int assetCounter = 0;

	private Asset dummyAsset(User user) {
		int i = assetCounter++;
		String base = SHA512SUM.toString().substring(0, 124);
		SHA512 sha = SHA512.fromString(base + String.format("%04x", i));
		Asset asset = assetDao().createAsset(user, sha, IMAGE_MIMETYPE, "library-membership-" + i + ".png", DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

}
