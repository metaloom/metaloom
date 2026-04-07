package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

public class AssetDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<AssetDao, Asset> {

	private int metaCounter = 100;

	@Override
	public AssetDao getDao() {
		return assetDao();
	}

	/**
	 * Generate a unique SHA-512 hash based on the index to avoid PK collisions.
	 */
	private SHA512 uniqueSha(int i) {
		String base = SHA512SUM.toString().substring(0, 124);
		return SHA512.fromString(base + String.format("%04x", i));
	}

	@Override
	public Asset createElement(User user, int i) {
		return assetDao().createAsset(user.getUuid(), uniqueSha(i), IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
	}

	@Override
	public void assertCreate(Asset createdElement) {
		assertNotNull(createdElement.getSHA512());
		assertEquals(IMAGE_MIMETYPE, createdElement.getMimeType());
		assertEquals(DUMMY_IMAGE_FILENAME, createdElement.getFilename());
		assertEquals(DUMMY_IMAGE_ORIGIN, createdElement.getInitialOrigin());
		assertEquals(42L, createdElement.getSize());
	}

	@Override
	public void updateElement(Asset element) {
		element.setMimeType("new mimetype");
	}

	@Override
	public void assertUpdate(Asset updatedAsset) {
		assertEquals("new mimetype", updatedAsset.getMimeType());
	}

	private Asset createUniqueAsset(User user) {
		return createElement(user, metaCounter++);
	}

	@Test
	public void testSetMeta() {
		User user = dummyUser();
		Asset asset = createUniqueAsset(user);
		JsonObject meta = new JsonObject().put("rating", 5).put("source", "import");
		asset.setMeta(meta);
		assetDao().store(asset);

		Asset loaded = assetDao().load(asset.getUuid());
		assertNotNull(loaded.getMeta());
		assertEquals(5, loaded.getMeta().getInteger("rating"));
		assertEquals("import", loaded.getMeta().getString("source"));
	}

	@Test
	public void testUpdateMeta() {
		User user = dummyUser();
		Asset asset = createUniqueAsset(user);
		asset.setMeta(new JsonObject().put("key", "original"));
		assetDao().store(asset);

		// Update meta with new content
		Asset loaded = assetDao().load(asset.getUuid());
		loaded.setMeta(new JsonObject().put("key", "updated").put("extra", true));
		assetDao().update(loaded);

		Asset reloaded = assetDao().load(asset.getUuid());
		assertNotNull(reloaded.getMeta());
		assertEquals("updated", reloaded.getMeta().getString("key"));
		assertEquals(true, reloaded.getMeta().getBoolean("extra"));
	}

	@Test
	public void testRemoveMeta() {
		User user = dummyUser();
		Asset asset = createUniqueAsset(user);
		asset.setMeta(new JsonObject().put("temp", "data"));
		assetDao().store(asset);

		// Clear meta by setting null
		Asset loaded = assetDao().load(asset.getUuid());
		assertNotNull(loaded.getMeta());
		loaded.setMeta(null);
		assetDao().update(loaded);

		Asset reloaded = assetDao().load(asset.getUuid());
		assertNull(reloaded.getMeta());
	}

	@Test
	public void testCreateWithoutMeta() {
		User user = dummyUser();
		Asset asset = createUniqueAsset(user);
		assetDao().store(asset);

		Asset loaded = assetDao().load(asset.getUuid());
		assertNull(loaded.getMeta());
	}

	@Test
	public void testMetaWithNestedObject() {
		User user = dummyUser();
		Asset asset = createUniqueAsset(user);
		JsonObject nested = new JsonObject()
			.put("tags", new JsonObject().put("genre", "landscape").put("mood", "calm"))
			.put("score", 9.5);
		asset.setMeta(nested);
		assetDao().store(asset);

		Asset loaded = assetDao().load(asset.getUuid());
		assertNotNull(loaded.getMeta());
		assertEquals("landscape", loaded.getMeta().getJsonObject("tags").getString("genre"));
		assertEquals(9.5, loaded.getMeta().getDouble("score"), 0.001);
	}

}
