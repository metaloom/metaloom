package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareDao;
import io.metaloom.loom.db.model.share.ShareTargetType;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public class ShareDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<ShareDao, Share> {

	@Override
	public ShareDao getDao() {
		return shareDao();
	}

	@Override
	public Share createElement(User user, int i) {
		return getDao().createAssetShare(user.getUuid(), asset().getUuid(), "slug_" + i + "_" + System.nanoTime());
	}

	@Override
	public void assertCreate(Share createdElement) {
		assertEquals(ShareTargetType.ASSET, createdElement.targetType());
		assertEquals(asset().getUuid(), createdElement.getAssetUuid());
		assertNull(createdElement.getCollectionUuid(), "An asset share must not also name a collection");
		assertTrue(createdElement.getAllowDownload(), "Downloading is on by default");
		assertFalse(createdElement.getAllowComments(), "Guest feedback is off by default");
		assertEquals(0, createdElement.getViewCount());
	}

	@Override
	public void assertUpdate(Share updatedElement) {
		assertFalse(updatedElement.getAllowDownload());
	}

	@Override
	public void updateElement(Share share) {
		share.setAllowDownload(false);
	}

	@Test
	public void testLoadBySlug() {
		Share share = storedAssetShare("lookup_" + System.nanoTime());

		Share loaded = getDao().loadBySlug(share.getSlug());
		assertNotNull(loaded, "The share must be reachable by its slug");
		assertEquals(share.getUuid(), loaded.getUuid());

		assertNull(getDao().loadBySlug("no-such-slug-" + System.nanoTime()), "An unknown slug resolves to nothing");
		assertNull(getDao().loadBySlug(null), "A null slug must not blow up");
	}

	@Test
	public void testSlugExists() {
		Share share = storedAssetShare("exists_" + System.nanoTime());
		assertTrue(getDao().slugExists(share.getSlug()));
		assertFalse(getDao().slugExists("free-" + System.nanoTime()));
	}

	/**
	 * A collection share carries the collection and nothing else. The CHECK in V2.97 refuses a row that sets both targets, so this also proves the
	 * DAO is not quietly filling in the other one.
	 */
	@Test
	public void testCreateCollectionShare() {
		User user = dummyUser();
		Collection collection = collectionDao().createCollection(user, "share_target_" + System.nanoTime());
		collectionDao().store(collection);

		Share share = getDao().createCollectionShare(user.getUuid(), collection.getUuid(), "coll_" + System.nanoTime());
		getDao().store(share);

		Share loaded = getDao().load(share.getUuid());
		assertEquals(ShareTargetType.COLLECTION, loaded.targetType());
		assertEquals(collection.getUuid(), loaded.getCollectionUuid());
		assertNull(loaded.getAssetUuid());
		assertEquals(collection.getUuid(), loaded.getTargetUuid());
	}

	@Test
	public void testLoadPageByTarget() {
		User user = dummyUser();
		Collection collection = collectionDao().createCollection(user, "share_paging_" + System.nanoTime());
		collectionDao().store(collection);

		Share assetShare = storedAssetShare("by_asset_" + System.nanoTime());
		Share collectionShare = getDao().createCollectionShare(user.getUuid(), collection.getUuid(), "by_coll_" + System.nanoTime());
		getDao().store(collectionShare);

		Page<Share> byAsset = getDao().loadPageByAsset(asset().getUuid(), null, 25);
		assertTrue(contains(byAsset, assetShare), "The asset share must be reachable from its asset");
		assertFalse(contains(byAsset, collectionShare), "A collection share must not surface under an asset");

		Page<Share> byCollection = getDao().loadPageByCollection(collection.getUuid(), null, 25);
		assertEquals(1, byCollection.totalCount());
		assertEquals(collectionShare.getUuid(), byCollection.first().getUuid());
	}

	/**
	 * The first visit names the share; later ones only bump the counter.
	 *
	 * <p>
	 * This is the whole of the chosen identity model, so it is worth an explicit test: a second visitor silently renaming the first one's feedback
	 * would be indistinguishable from working software right up until somebody read the review.
	 * </p>
	 */
	@Test
	public void testRecordVisitNamesTheShareOnlyOnce() {
		Share share = storedAssetShare("visit_" + System.nanoTime());
		assertNull(share.getVisitorName());
		assertEquals(0, share.getViewCount());

		getDao().recordVisit(share.getUuid(), "Maria");
		Share afterFirst = getDao().load(share.getUuid());
		assertEquals("Maria", afterFirst.getVisitorName());
		assertEquals(1, afterFirst.getViewCount());
		assertNotNull(afterFirst.getFirstVisitedAt());
		assertNotNull(afterFirst.getLastViewedAt());

		getDao().recordVisit(share.getUuid(), "Somebody Else");
		Share afterSecond = getDao().load(share.getUuid());
		assertEquals("Maria", afterSecond.getVisitorName(), "The first visitor names the share; a later one must not rename it");
		assertEquals(2, afterSecond.getViewCount(), "Every redeemed session counts");
		assertEquals(afterFirst.getFirstVisitedAt(), afterSecond.getFirstVisitedAt(), "The first-visit stamp is written once");
	}

	@Test
	public void testExpiryIsReadBack() {
		User user = dummyUser();
		Instant expiry = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

		Share share = getDao().createAssetShare(user.getUuid(), asset().getUuid(), "expiry_" + System.nanoTime());
		share.setExpiresAt(expiry);
		getDao().store(share);

		Share loaded = getDao().load(share.getUuid());
		assertNotNull(loaded.getExpiresAt());
		assertFalse(loaded.isExpired(), "A share expiring in a week has not expired");

		Share lapsed = getDao().createAssetShare(user.getUuid(), asset().getUuid(), "lapsed_" + System.nanoTime());
		lapsed.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
		getDao().store(lapsed);
		assertTrue(getDao().load(lapsed.getUuid()).isExpired(), "A share whose expiry has passed reports itself expired");
	}

	@Test
	public void testPasswordProtectionFlag() {
		Share open = storedAssetShare("open_" + System.nanoTime());
		assertFalse(open.isPasswordProtected());

		User user = dummyUser();
		Share locked = getDao().createAssetShare(user.getUuid(), asset().getUuid(), "locked_" + System.nanoTime());
		locked.setPasswordHash("$2a$10$notarealhashbutlongenoughtolooklikeone");
		getDao().store(locked);
		assertTrue(getDao().load(locked.getUuid()).isPasswordProtected());
	}

	private boolean contains(Page<Share> page, Share share) {
		for (Share entry : page) {
			if (entry.getUuid().equals(share.getUuid())) {
				return true;
			}
		}
		return false;
	}

	private Share storedAssetShare(String slug) {
		Share share = getDao().createAssetShare(dummyUser().getUuid(), asset().getUuid(), slug);
		getDao().store(share);
		return share;
	}
}
