package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.dedup.DedupGroup;
import io.metaloom.loom.db.model.dedup.DedupGroupDao;
import io.metaloom.loom.db.model.dedup.DedupGroupMember;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Covers the deduplication review model: group + member persistence, the review-queue queries, status transitions and — most importantly — the
 * delete-cascade contract (deleting a group removes exactly its members; deleting a member asset removes its membership and nulls the KEEP pointer
 * without destroying the review record).
 */
public class DedupGroupDaoTest extends AbstractJooqTest {

	private static final String ALGO = "metaloom-multisector-v1";

	private DedupGroupDao dao() {
		return daos().dedupGroupDao();
	}

	private SHA512 uniqueSha(int i) {
		return SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", i));
	}

	private Asset storeAsset(User user, int i) {
		Asset asset = assetDao().createAsset(user.getUuid(), uniqueSha(i), IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	private DedupGroup storeGroup(UUID keep, UUID dup, User user) {
		DedupGroup group = dao().createGroup(user.getUuid(), ALGO);
		group.setKeepAssetUuid(keep);
		group.setScore(0.85f);
		dao().storeGroup(group);
		dao().addMember(group.getUuid(), keep, DedupGroupMember.ROLE_KEEP, 1.0f, 2000L, 0L);
		dao().addMember(group.getUuid(), dup, DedupGroupMember.ROLE_DUP, 0.85f, 1000L, 0L);
		return group;
	}

	@Test
	public void testStoreAndLoad() {
		User user = dummyUser();
		Asset keep = storeAsset(user, 1);
		Asset dup = storeAsset(user, 2);

		DedupGroup group = storeGroup(keep.getUuid(), dup.getUuid(), user);

		DedupGroup loaded = dao().loadGroup(group.getUuid());
		assertNotNull(loaded);
		assertEquals(DedupGroup.STATUS_PENDING, loaded.getStatus());
		assertEquals(ALGO, loaded.getAlgorithm());
		assertEquals(keep.getUuid(), loaded.getKeepAssetUuid());

		List<DedupGroupMember> members = dao().loadMembers(group.getUuid());
		assertEquals(2, members.size());
		assertTrue(members.stream().anyMatch(m -> DedupGroupMember.ROLE_KEEP.equals(m.getRole()) && keep.getUuid().equals(m.getAssetUuid())));
		assertTrue(members.stream().anyMatch(m -> DedupGroupMember.ROLE_DUP.equals(m.getRole()) && dup.getUuid().equals(m.getAssetUuid())));
	}

	@Test
	public void testListByStatusAndAsset() {
		User user = dummyUser();
		Asset keep = storeAsset(user, 3);
		Asset dup = storeAsset(user, 4);
		DedupGroup group = storeGroup(keep.getUuid(), dup.getUuid(), user);

		assertTrue(dao().listByStatus(DedupGroup.STATUS_PENDING).stream().anyMatch(g -> g.getUuid().equals(group.getUuid())));
		assertTrue(dao().listByStatus(DedupGroup.STATUS_CONFIRMED).stream().noneMatch(g -> g.getUuid().equals(group.getUuid())));

		assertEquals(1, dao().listByAsset(dup.getUuid()).size());
		assertEquals(group.getUuid(), dao().listByAsset(dup.getUuid()).get(0).getUuid());
	}

	@Test
	public void testFindPendingByKeepAndUpdateStatus() {
		User user = dummyUser();
		Asset keep = storeAsset(user, 5);
		Asset dup = storeAsset(user, 6);
		DedupGroup group = storeGroup(keep.getUuid(), dup.getUuid(), user);

		DedupGroup found = dao().findPendingByKeep(keep.getUuid(), ALGO);
		assertNotNull(found, "The discovery node must be able to find its own PENDING group for idempotent upsert");
		assertEquals(group.getUuid(), found.getUuid());

		dao().updateStatus(group.getUuid(), DedupGroup.STATUS_CONFIRMED, keep.getUuid(), user.getUuid());
		DedupGroup confirmed = dao().loadGroup(group.getUuid());
		assertEquals(DedupGroup.STATUS_CONFIRMED, confirmed.getStatus());
		assertEquals(user.getUuid(), confirmed.getEditorUuid());

		// Once confirmed it is no longer a PENDING candidate for re-discovery.
		assertNull(dao().findPendingByKeep(keep.getUuid(), ALGO));
	}

	@Test
	public void testInvalidRoleIsRejected() {
		User user = dummyUser();
		Asset keep = storeAsset(user, 7);
		DedupGroup group = dao().createGroup(user.getUuid(), ALGO);
		dao().storeGroup(group);
		assertThrows(DataAccessException.class,
			() -> dao().addMember(group.getUuid(), keep.getUuid(), "MAYBE", 1.0f, 1L, 0L));
	}

	@Test
	public void testDeleteGroupCascadesMembers() {
		User user = dummyUser();
		Asset keep = storeAsset(user, 8);
		Asset dup = storeAsset(user, 9);
		DedupGroup group = storeGroup(keep.getUuid(), dup.getUuid(), user);
		assertEquals(2, dao().loadMembers(group.getUuid()).size());

		dao().deleteGroup(group.getUuid());

		assertNull(dao().loadGroup(group.getUuid()));
		assertTrue(dao().loadMembers(group.getUuid()).isEmpty(), "Deleting a group must remove exactly its members");
	}

	@Test
	public void testDeleteAssetRemovesMembershipAndNullsKeep() {
		User user = dummyUser();
		Asset keep = storeAsset(user, 10);
		Asset dup = storeAsset(user, 11);
		DedupGroup group = storeGroup(keep.getUuid(), dup.getUuid(), user);

		// Deleting the KEEP asset must not destroy the review record, but must null the denormalised pointer and drop its membership.
		assetDao().delete(keep.getUuid());

		DedupGroup survived = dao().loadGroup(group.getUuid());
		assertNotNull(survived, "Deleting an asset must not delete the whole review group");
		assertNull(survived.getKeepAssetUuid(), "keep_asset_uuid must be nulled (ON DELETE SET NULL)");

		List<DedupGroupMember> members = dao().loadMembers(group.getUuid());
		assertEquals(1, members.size(), "The deleted asset's membership must cascade away");
		assertEquals(dup.getUuid(), members.get(0).getAssetUuid());
	}
}
