package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class AssetJsonCompDaoTest extends AbstractJooqTest {

	private AssetComponentDao dao() {
		return assetComponentDao();
	}

	private Asset createTestAsset() {
		User user = dummyUser();
		return createAsset(user);
	}

	@Test
	public void testCreate() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "yolo-detector");
		comp.setSchemaType("yolo-detection");
		comp.setData(new JsonObject().put("detections", 3).put("model", "yolov8"));
		dao().storeJsonComp(comp);

		assertNotNull(comp.getUuid());
		assertEquals(asset.getUuid(), comp.getAssetUuid());
		assertEquals("yolo-detector", comp.getSource());
		assertEquals("yolo-detection", comp.getSchemaType());
		assertNotNull(comp.getData());
		assertEquals(3, comp.getData().getInteger("detections"));
		assertEquals("yolov8", comp.getData().getString("model"));
	}

	@Test
	public void testLoad() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "face-embedder");
		comp.setSchemaType("face-embedding");
		comp.setData(new JsonObject().put("faces", 2));
		dao().storeJsonComp(comp);
		UUID compUuid = comp.getUuid();

		AssetJsonComp loaded = dao().loadJsonComp(compUuid);
		assertNotNull(loaded);
		assertEquals(compUuid, loaded.getUuid());
		assertEquals(asset.getUuid(), loaded.getAssetUuid());
		assertEquals("face-embedder", loaded.getSource());
		assertEquals("face-embedding", loaded.getSchemaType());
		assertNotNull(loaded.getData());
		assertEquals(2, loaded.getData().getInteger("faces"));
	}

	@Test
	public void testLoadNotFound() {
		AssetJsonComp loaded = dao().loadJsonComp(UUID.randomUUID());
		assertNull(loaded);
	}

	@Test
	public void testLoadByAsset() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		dao().storeJsonComp(dao().createJsonComp(user.getUuid(), asset.getUuid(), "source-a"));
		dao().storeJsonComp(dao().createJsonComp(user.getUuid(), asset.getUuid(), "source-b"));

		List<AssetJsonComp> comps = dao().loadJsonComps(asset.getUuid());
		assertEquals(2, comps.size());
	}

	@Test
	public void testLoadByAssetEmpty() {
		List<AssetJsonComp> comps = dao().loadJsonComps(UUID.randomUUID());
		assertNotNull(comps);
		assertTrue(comps.isEmpty());
	}

	@Test
	public void testUpdate() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "detector");
		comp.setSchemaType("v1");
		comp.setData(new JsonObject().put("count", 1));
		dao().storeJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		loaded.setSchemaType("v2");
		loaded.setData(new JsonObject().put("count", 5).put("extra", true));
		loaded.setSource("detector-updated");
		dao().updateJsonComp(loaded);

		AssetJsonComp reloaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(reloaded);
		assertEquals("v2", reloaded.getSchemaType());
		assertEquals("detector-updated", reloaded.getSource());
		assertNotNull(reloaded.getData());
		assertEquals(5, reloaded.getData().getInteger("count"));
		assertEquals(true, reloaded.getData().getBoolean("extra"));
	}

	@Test
	public void testDelete() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "source");
		dao().storeJsonComp(comp);
		UUID compUuid = comp.getUuid();

		assertNotNull(dao().loadJsonComp(compUuid));
		dao().deleteJsonComp(compUuid);
		assertNull(dao().loadJsonComp(compUuid));
	}

	@Test
	public void testNullData() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "empty-source");
		comp.setSchemaType("empty");
		// data left null
		dao().storeJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(loaded);
		assertEquals("empty", loaded.getSchemaType());
		assertNull(loaded.getData());
	}

	@Test
	public void testComplexJsonData() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		JsonObject nested = new JsonObject()
			.put("labels", new io.vertx.core.json.JsonArray().add("cat").add("dog"))
			.put("confidence", 0.95)
			.put("metadata", new JsonObject().put("model", "yolov8").put("version", 3));

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "classifier");
		comp.setSchemaType("classification");
		comp.setData(nested);
		dao().storeJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(loaded);
		assertNotNull(loaded.getData());
		assertEquals(0.95, loaded.getData().getDouble("confidence"), 0.001);
		assertEquals(2, loaded.getData().getJsonArray("labels").size());
		assertEquals("cat", loaded.getData().getJsonArray("labels").getString(0));
		assertEquals("yolov8", loaded.getData().getJsonObject("metadata").getString("model"));
	}

	@Test
	public void testUpdateDataToNull() {
		Asset asset = createTestAsset();
		User user = dummyUser();

		AssetJsonComp comp = dao().createJsonComp(user.getUuid(), asset.getUuid(), "source");
		comp.setData(new JsonObject().put("key", "value"));
		dao().storeJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(loaded.getData());

		loaded.setData(null);
		dao().updateJsonComp(loaded);

		AssetJsonComp reloaded = dao().loadJsonComp(comp.getUuid());
		assertNull(reloaded.getData());
	}
}
