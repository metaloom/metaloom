package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixDao;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * CRUD and meta coverage for {@link RemixDao}.
 *
 * <p>
 * Membership lives in {@link RemixMemberDaoTest} on purpose: a DAO test class much over twenty
 * methods exhausts the pooled-database provider, which is the same reason {@code TagPlacementDaoTest}
 * was split out of {@code TagDaoTest}.
 * </p>
 */
public class RemixDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<RemixDao, Remix> {

	@Override
	public RemixDao getDao() {
		return remixDao();
	}

	@Override
	public Remix createElement(User user, int i) {
		// The name has to vary with i: the paging testcase inserts 1024 elements.
		return getDao().createRemix(user, "remix_" + i);
	}

	@Override
	public void assertCreate(Remix createdElement) {
		assertEquals("remix_" + 0, createdElement.getName());
	}

	@Override
	public void assertUpdate(Remix updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Remix remix) {
		remix.setName("new_name");
	}

	@Test
	public void testDescriptionRoundTrip() {
		User user = dummyUser();
		Remix remix = getDao().createRemix(user, "described");
		remix.setDescription("The beach clip and its cuts");
		getDao().store(remix);

		Remix loaded = getDao().load(remix.getUuid());
		assertEquals("The beach clip and its cuts", loaded.getDescription());
	}

	@Test
	public void testSetMeta() {
		User user = dummyUser();
		Remix remix = getDao().createRemix(user, "with_meta");
		remix.setMeta(new JsonObject().put("origin", "import"));
		getDao().store(remix);

		Remix loaded = getDao().load(remix.getUuid());
		assertEquals(new JsonObject().put("origin", "import"), loaded.getMeta());
	}

	@Test
	public void testMetaWithNestedObject() {
		User user = dummyUser();
		Remix remix = getDao().createRemix(user, "nested_meta");
		JsonObject meta = new JsonObject().put("source", new JsonObject().put("tool", "ffmpeg").put("pass", 2));
		remix.setMeta(meta);
		getDao().store(remix);

		assertEquals(meta, getDao().load(remix.getUuid()).getMeta(), "The nested object must survive the JSONB round trip");
	}

	@Test
	public void testCreateWithoutMeta() {
		User user = dummyUser();
		Remix remix = getDao().createRemix(user, "no_meta");
		getDao().store(remix);

		assertNull(getDao().load(remix.getUuid()).getMeta());
	}

	/**
	 * Deleting the source asset nulls the pointer instead of taking the remix with it.
	 *
	 * <p>
	 * {@code V2.100} declares {@code ON DELETE SET NULL} on {@code remix.source_asset_uuid} and
	 * {@code ON DELETE CASCADE} on the member row deliberately: losing the original must not also
	 * lose the group holding everything derived from it.
	 * </p>
	 */
	@Test
	public void testDeletingSourceAssetKeepsTheRemix() {
		User user = dummyUser();
		Remix remix = getDao().createRemix(user, "orphaned_source");
		getDao().store(remix);

		Asset source = dummyAsset(user);
		Asset derived = dummyAsset(user);
		getDao().linkAsset(remix.getUuid(), source.getUuid(), RemixRole.SOURCE, null, user.getUuid());
		getDao().linkAsset(remix.getUuid(), derived.getUuid(), RemixRole.DERIVED, null, user.getUuid());

		assertEquals(source.getUuid(), getDao().load(remix.getUuid()).getSourceAssetUuid());

		assetDao().delete(source.getUuid());

		Remix loaded = getDao().load(remix.getUuid());
		assertNotNull(loaded, "The remix must survive deletion of its source asset");
		assertNull(loaded.getSourceAssetUuid(), "The source pointer must be nulled rather than dangling");
		assertEquals(1, getDao().countAssets(remix.getUuid()), "Only the source's membership should have cascaded");
	}

	private int assetCounter = 0;

	private Asset dummyAsset(User user) {
		int i = assetCounter++;
		String base = SHA512SUM.toString().substring(0, 124);
		SHA512 sha = SHA512.fromString(base + String.format("%04x", i));
		Asset asset = assetDao().createAsset(user, sha, IMAGE_MIMETYPE, "remix-" + i + ".png", DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

}
