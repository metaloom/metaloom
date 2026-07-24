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
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class AssetJsonCompDaoTest extends AbstractJooqTest {

	private AssetComponentDao dao() {
		return assetComponentDao();
	}

	private AssetJsonComp comp(String nodeKind, String schemaType) {
		Asset asset = asset();
		User user = dummyUser();
		return dao().createJsonComp(user.getUuid(), asset.getUuid(), nodeKind).setSchemaType(schemaType);
	}

	@Test
	public void testCreate() {
		Asset asset = asset();

		AssetJsonComp comp = comp("yolo-detector", "yolo-detection");
		comp.setData(new JsonObject().put("detections", 3).put("model", "yolov8"));
		dao().storeJsonComp(comp);

		assertNotNull(comp.getUuid());
		assertEquals(asset.getUuid(), comp.getAssetUuid());
		assertEquals("yolo-detector", comp.getNodeKind());
		assertEquals("yolo-detection", comp.getSchemaType());
		assertNotNull(comp.getData());
		assertEquals(3, comp.getData().getInteger("detections"));
		assertEquals("yolov8", comp.getData().getString("model"));
	}

	@Test
	public void testLoad() {
		Asset asset = asset();

		AssetJsonComp comp = comp("face-embedder", "face-embedding");
		comp.setData(new JsonObject().put("faces", 2));
		dao().storeJsonComp(comp);
		UUID compUuid = comp.getUuid();

		AssetJsonComp loaded = dao().loadJsonComp(compUuid);
		assertNotNull(loaded);
		assertEquals(compUuid, loaded.getUuid());
		assertEquals(asset.getUuid(), loaded.getAssetUuid());
		assertEquals("face-embedder", loaded.getNodeKind());
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
		Asset asset = asset();

		dao().storeJsonComp(comp("source-a", "shape"));
		dao().storeJsonComp(comp("source-b", "shape"));

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
		AssetJsonComp comp = comp("detector", "v1");
		comp.setData(new JsonObject().put("count", 1));
		dao().storeJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		loaded.setSchemaType("v2");
		loaded.setData(new JsonObject().put("count", 5).put("extra", true));
		loaded.setNodeKind("detector-updated");
		dao().updateJsonComp(loaded);

		AssetJsonComp reloaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(reloaded);
		assertEquals("v2", reloaded.getSchemaType());
		assertEquals("detector-updated", reloaded.getNodeKind());
		assertNotNull(reloaded.getData());
		assertEquals(5, reloaded.getData().getInteger("count"));
		assertEquals(true, reloaded.getData().getBoolean("extra"));
	}

	@Test
	public void testDelete() {
		AssetJsonComp comp = comp("source", "shape");
		dao().storeJsonComp(comp);
		UUID compUuid = comp.getUuid();

		assertNotNull(dao().loadJsonComp(compUuid));
		dao().deleteJsonComp(compUuid);
		assertNull(dao().loadJsonComp(compUuid));
	}

	@Test
	public void testNullDataBecomesEmptyObject() {
		AssetJsonComp comp = comp("empty-source", "empty");
		// data left null - the column is NOT NULL, "the node produced nothing" is expressed
		// by the processing ledger rather than by a NULL payload
		dao().storeJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(loaded);
		assertEquals("empty", loaded.getSchemaType());
		assertNotNull(loaded.getData());
		assertTrue(loaded.getData().isEmpty());
	}

	@Test
	public void testComplexJsonData() {
		JsonObject nested = new JsonObject()
			.put("labels", new io.vertx.core.json.JsonArray().add("cat").add("dog"))
			.put("confidence", 0.95)
			.put("metadata", new JsonObject().put("model", "yolov8").put("version", 3));

		AssetJsonComp comp = comp("classifier", "classification");
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

	/**
	 * Worked case: an LLM node configured with three prompts writes three components discriminated by variant.
	 */
	@Test
	public void testVariantsCoexist() {
		Asset asset = asset();

		for (String prompt : new String[] { "summary", "keywords", "mood" }) {
			AssetJsonComp comp = comp("llm", "llm-answer");
			comp.setVariant(prompt);
			comp.setData(new JsonObject().put("answer", prompt));
			dao().upsertJsonComp(comp);
		}

		assertEquals(3, dao().loadJsonComps(asset.getUuid()).size());
		assertEquals("keywords", dao().loadJsonComp(asset.getUuid(), "llm", "llm-answer", "keywords").getData().getString("answer"));
	}

	@Test
	public void testDuplicateIdentityIsRejected() {
		AssetJsonComp first = comp("llm", "llm-answer");
		first.setVariant("summary");
		dao().storeJsonComp(first);

		AssetJsonComp duplicate = comp("llm", "llm-answer");
		duplicate.setVariant("summary");
		assertThrows(DataAccessException.class, () -> dao().storeJsonComp(duplicate));
	}

	@Test
	public void testUpsertReplacesInPlace() {
		Asset asset = asset();

		AssetJsonComp first = comp("llm", "llm-answer");
		first.setVariant("summary");
		first.setProducerVersion("llama-3.1");
		first.setData(new JsonObject().put("answer", "old"));
		dao().upsertJsonComp(first);

		AssetJsonComp second = comp("llm", "llm-answer");
		second.setVariant("summary");
		second.setProducerVersion("llama-3.3");
		second.setData(new JsonObject().put("answer", "new"));
		dao().upsertJsonComp(second);

		assertEquals(first.getUuid(), second.getUuid(), "The upsert must reuse the existing row");
		assertEquals(1, dao().loadJsonComps(asset.getUuid()).size());

		AssetJsonComp loaded = dao().loadJsonComp(asset.getUuid(), "llm", "llm-answer", "summary");
		assertEquals("new", loaded.getData().getString("answer"));
		assertEquals("llama-3.3", loaded.getProducerVersion(), "A model upgrade rewrites the producer version");
	}

	@Test
	public void testMachineWrittenRowHasNoCreator() {
		Asset asset = asset();

		AssetJsonComp comp = dao().createJsonComp(null, asset.getUuid(), "captioning");
		comp.setSchemaType("caption");
		comp.setData(new JsonObject().put("caption", "a cat on a sofa"));
		dao().upsertJsonComp(comp);

		AssetJsonComp loaded = dao().loadJsonComp(comp.getUuid());
		assertNotNull(loaded);
		assertNull(loaded.getCreatorUuid());
		assertNull(loaded.getEditorUuid());
	}
}
