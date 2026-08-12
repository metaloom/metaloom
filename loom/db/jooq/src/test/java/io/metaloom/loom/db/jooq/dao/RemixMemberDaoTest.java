package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixDao;
import io.metaloom.loom.db.model.remix.RemixMember;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;

/**
 * Membership operations of {@link RemixDao} - link, unlink, list, and the source-role invariant.
 *
 * <p>
 * Split from {@link RemixDaoTest} so neither class runs long enough to exhaust the pooled-database
 * provider.
 * </p>
 */
public class RemixMemberDaoTest extends AbstractJooqTest {

	private RemixDao dao() {
		return remixDao();
	}

	/** {@link Page} is an {@link Iterable}, not a list; collect it where a test needs indexing. */
	private static List<RemixMember> list(Page<RemixMember> page) {
		List<RemixMember> out = new ArrayList<>();
		page.forEach(out::add);
		return out;
	}

	private List<RemixMember> members(Remix remix) {
		return list(dao().loadMembers(remix.getUuid(), null, 25));
	}

	private int assetCounter = 0;

	private Asset dummyAsset(User user) {
		int i = assetCounter++;
		String base = SHA512SUM.toString().substring(0, 124);
		SHA512 sha = SHA512.fromString(base + String.format("%04x", i));
		Asset asset = assetDao().createAsset(user, sha, IMAGE_MIMETYPE, "remix-member-" + i + ".png", DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	private Remix remix(User user, String name) {
		Remix remix = dao().createRemix(user, name);
		dao().store(remix);
		return remix;
	}

	@Test
	public void testLinkAsset() {
		User user = dummyUser();
		Remix remix = remix(user, "link");
		Asset asset = dummyAsset(user);

		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());

		assertTrue(dao().containsAsset(remix.getUuid(), asset.getUuid()));
		assertEquals(1, dao().countAssets(remix.getUuid()));
	}

	/**
	 * Re-adding a member updates its role and ordinal instead of raising a duplicate-key error.
	 *
	 * <p>
	 * The UI's "combine into remix" action posts whatever the user had selected, which routinely
	 * overlaps what the remix already holds. Making the caller diff the selection first would push
	 * that work onto every caller for no gain.
	 * </p>
	 */
	@Test
	public void testLinkAssetIsIdempotentAndUpdates() {
		User user = dummyUser();
		Remix remix = remix(user, "idempotent");
		Asset asset = dummyAsset(user);

		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, 1, user.getUuid());
		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, 7, user.getUuid());

		assertEquals(1, dao().countAssets(remix.getUuid()), "The asset should be a member exactly once");
		RemixMember member = members(remix).get(0);
		assertEquals(7, member.getOrdinal(), "The second link should have updated the ordinal");
	}

	@Test
	public void testUnlinkAsset() {
		User user = dummyUser();
		Remix remix = remix(user, "unlink");
		Asset asset = dummyAsset(user);

		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());
		dao().unlinkAsset(remix.getUuid(), asset.getUuid());

		assertFalse(dao().containsAsset(remix.getUuid(), asset.getUuid()));
		assertEquals(0, dao().countAssets(remix.getUuid()));
	}

	/** Removing the source member must also clear the denormalised pointer that mirrored it. */
	@Test
	public void testUnlinkSourceClearsThePointer() {
		User user = dummyUser();
		Remix remix = remix(user, "unlink_source");
		Asset source = dummyAsset(user);

		dao().linkAsset(remix.getUuid(), source.getUuid(), RemixRole.SOURCE, null, user.getUuid());
		assertEquals(source.getUuid(), dao().load(remix.getUuid()).getSourceAssetUuid());

		dao().unlinkAsset(remix.getUuid(), source.getUuid());

		assertNull(dao().load(remix.getUuid()).getSourceAssetUuid(),
			"The source pointer must not outlive the membership it mirrors");
	}

	@Test
	public void testLoadMembersProjectsTheAsset() {
		User user = dummyUser();
		Remix remix = remix(user, "projection");
		Asset asset = dummyAsset(user);
		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.SOURCE, 3, user.getUuid());

		Page<RemixMember> page = dao().loadMembers(remix.getUuid(), null, 25);
		assertEquals(1, page.totalCount());
		RemixMember member = list(page).get(0);

		assertEquals(asset.getUuid(), member.getAssetUuid());
		assertEquals(RemixRole.SOURCE, member.getRole());
		assertTrue(member.isSource());
		assertEquals(3, member.getOrdinal());
		assertNotNull(member.getUuid(), "The membership carries its own uuid, distinct from the asset's");
		assertEquals(asset.getFilename(), member.getFilename(), "The asset side of the join should be projected");
		assertEquals(asset.getSHA512().toString(), member.getSha512sum());
		assertEquals(user.getUuid(), member.getCreatorUuid());
		assertNotNull(member.getCreated());
	}

	/** Keyset paging over the members: every element seen exactly once, in insertion order. */
	@Test
	public void testLoadMembersPaging() {
		User user = dummyUser();
		Remix remix = remix(user, "paging");
		for (int i = 0; i < 7; i++) {
			dao().linkAsset(remix.getUuid(), dummyAsset(user).getUuid(), RemixRole.DERIVED, i, user.getUuid());
		}

		List<UUID> seen = new ArrayList<>();
		UUID cursor = null;
		for (int page = 0; page < 5; page++) {
			Page<RemixMember> members = dao().loadMembers(remix.getUuid(), cursor, 3);
			assertEquals(7, members.totalCount(), "The total must count every member, not the page");
			List<RemixMember> batch = list(members);
			if (batch.isEmpty()) {
				break;
			}
			batch.forEach(m -> seen.add(m.getUuid()));
			cursor = batch.get(batch.size() - 1).getUuid();
		}

		assertEquals(7, seen.size(), "Every member should be seen exactly once across the pages");
		assertEquals(7, seen.stream().distinct().count(), "No member should be repeated across pages");
	}

	/** A cursor whose row was removed between pages yields nothing rather than restarting from the top. */
	@Test
	public void testLoadMembersWithStaleCursor() {
		User user = dummyUser();
		Remix remix = remix(user, "stale_cursor");
		Asset asset = dummyAsset(user);
		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());

		UUID cursor = members(remix).get(0).getUuid();
		dao().unlinkAsset(remix.getUuid(), asset.getUuid());

		assertTrue(list(dao().loadMembers(remix.getUuid(), cursor, 25)).isEmpty());
	}

	@Test
	public void testLoadPageByAsset() {
		User user = dummyUser();
		Asset asset = dummyAsset(user);
		Remix first = remix(user, "by_asset_a");
		Remix second = remix(user, "by_asset_b");
		dao().linkAsset(first.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());
		dao().linkAsset(second.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());

		assertEquals(2, dao().loadPageByAsset(asset.getUuid(), null, 25).totalCount(),
			"Both remixes should be reachable from the asset");
	}

	/** {@code setSource} demotes the incumbent and moves the pointer in one transaction. */
	@Test
	public void testSetSourceMovesRoleAndPointer() {
		User user = dummyUser();
		Remix remix = remix(user, "set_source");
		Asset first = dummyAsset(user);
		Asset second = dummyAsset(user);
		dao().linkAsset(remix.getUuid(), first.getUuid(), RemixRole.SOURCE, null, user.getUuid());
		dao().linkAsset(remix.getUuid(), second.getUuid(), RemixRole.DERIVED, null, user.getUuid());

		dao().setSource(remix.getUuid(), second.getUuid());

		assertEquals(second.getUuid(), dao().load(remix.getUuid()).getSourceAssetUuid());
		List<RemixMember> members = members(remix);
		assertEquals(1, members.stream().filter(RemixMember::isSource).count(), "Exactly one member may be the source");
		assertEquals(second.getUuid(), members.stream().filter(RemixMember::isSource).findFirst().orElseThrow().getAssetUuid());
	}

	@Test
	public void testSetSourceToNullClearsIt() {
		User user = dummyUser();
		Remix remix = remix(user, "clear_source");
		Asset asset = dummyAsset(user);
		dao().linkAsset(remix.getUuid(), asset.getUuid(), RemixRole.SOURCE, null, user.getUuid());

		dao().setSource(remix.getUuid(), null);

		assertNull(dao().load(remix.getUuid()).getSourceAssetUuid());
		assertFalse(members(remix).get(0).isSource());
	}

	/**
	 * Naming a non-member as the source is refused, and the incumbent survives.
	 *
	 * <p>
	 * The demotion runs before the promotion, because {@code remix_member_single_source} would
	 * otherwise reject the second SOURCE. That ordering is only safe if a failed promotion rolls the
	 * demotion back with it, which is what this asserts.
	 * </p>
	 */
	@Test
	public void testSetSourceRejectsNonMemberAndRollsBack() {
		User user = dummyUser();
		Remix remix = remix(user, "reject_source");
		Asset member = dummyAsset(user);
		Asset stranger = dummyAsset(user);
		dao().linkAsset(remix.getUuid(), member.getUuid(), RemixRole.SOURCE, null, user.getUuid());

		assertThrows(RuntimeException.class, () -> dao().setSource(remix.getUuid(), stranger.getUuid()));

		assertEquals(member.getUuid(), dao().load(remix.getUuid()).getSourceAssetUuid(),
			"The incumbent source must survive a refused change");
		assertTrue(members(remix).get(0).isSource(),
			"The demotion must have rolled back with the failed promotion");
	}

	/**
	 * Deleting a remix cascades its own memberships and nothing else.
	 *
	 * <p>
	 * A cascade one join column too wide would silently empty an unrelated remix holding the same
	 * asset, so the bystander is the point of the test.
	 * </p>
	 */
	@Test
	public void testDeleteCascadesOnlyItsOwnMemberships() {
		User user = dummyUser();
		Asset asset = dummyAsset(user);
		Remix victim = remix(user, "cascade_victim");
		Remix bystander = remix(user, "cascade_bystander");
		dao().linkAsset(victim.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());
		dao().linkAsset(bystander.getUuid(), asset.getUuid(), RemixRole.DERIVED, null, user.getUuid());

		dao().delete(victim.getUuid());

		assertNull(dao().load(victim.getUuid()), "The remix is gone");
		assertEquals(0, dao().countAssets(victim.getUuid()), "Its memberships must have cascaded");
		assertEquals(1, dao().countAssets(bystander.getUuid()), "The other remix must keep its member");
		assertNotNull(assetDao().load(asset.getUuid()), "The asset itself must survive");
	}

}
