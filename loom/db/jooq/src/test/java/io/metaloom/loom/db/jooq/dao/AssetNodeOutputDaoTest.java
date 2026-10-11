package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetNodeOutput;
import io.metaloom.loom.db.model.asset.AssetNodeOutputDao;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The node output reuse store (V2.116): one row per (asset, kind, reuse key), replaced in place, and removable per asset and kind.
 */
public class AssetNodeOutputDaoTest extends AbstractJooqTest {

	private AssetNodeOutputDao dao() {
		return daos().assetNodeOutputDao();
	}

	private static JsonObject outputs(String text) {
		return new JsonObject().put("transcript", new JsonObject()
			.put("contentType", "text/transcript")
			.put("cardinality", "ONE")
			.put("values", new JsonArray().add(text)));
	}

	private Asset otherAsset(int i) {
		SHA512 sha = SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", 0xa000 + i));
		Asset asset = assetDao().createAsset(dummyUser(), sha, IMAGE_MIMETYPE, "other_" + i + ".jpg", DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	@Test
	public void testUpsertAndLoadByKey() {
		UUID assetUuid = asset().getUuid();
		dao().upsert(assetUuid, "whisper", "key-load", "whisper-1", "large-v3", AssetNodeResult.STATE_SUCCESS, null, outputs("hello"), null);

		AssetNodeOutput loaded = dao().loadByKey(assetUuid, "whisper", "key-load");
		assertNotNull(loaded, "A stored output is found by its key");
		assertEquals("whisper-1", loaded.nodeId());
		assertEquals("large-v3", loaded.producerVersion());
		assertEquals(AssetNodeResult.STATE_SUCCESS, loaded.state());
		assertEquals("hello", loaded.outputs().getJsonObject("transcript").getJsonArray("values").getString(0));
		assertNotNull(loaded.created());
		assertNull(dao().loadByKey(assetUuid, "whisper", "key-unknown"), "Another key is a miss");
		assertNull(dao().loadByKey(assetUuid, "ocr", "key-load"), "The key is scoped by node kind");
	}

	/**
	 * A recompute writes the same key again. The newer answer replaces the older one in the same row, whichever node instance wrote it - node id is
	 * not part of the key, which is what lets a second pipeline find the first one's output.
	 */
	@Test
	public void testSameKeyReplacesAcrossNodeIds() {
		UUID assetUuid = asset().getUuid();
		AssetNodeOutput first = dao().upsert(assetUuid, "whisper", "key-replace", "pipeline-a-whisper", "v1", AssetNodeResult.STATE_SUCCESS, null,
			outputs("first"), null);
		AssetNodeOutput second = dao().upsert(assetUuid, "whisper", "key-replace", "pipeline-b-whisper", "v1", AssetNodeResult.STATE_SKIPPED,
			"no speech", outputs("second"), null);

		assertEquals(first.uuid(), second.uuid(), "The same row is updated");
		AssetNodeOutput loaded = dao().loadByKey(assetUuid, "whisper", "key-replace");
		assertEquals("pipeline-b-whisper", loaded.nodeId(), "The last writer is recorded");
		assertEquals(AssetNodeResult.STATE_SKIPPED, loaded.state());
		assertEquals("no speech", loaded.reason());
		assertEquals("second", loaded.outputs().getJsonObject("transcript").getJsonArray("values").getString(0));
		assertEquals(first.created(), loaded.created(), "The row keeps its first creation time");
	}

	/** Two configurations of one kind are two answers, not one row overwriting the other. */
	@Test
	public void testDifferentKeysAreSeparateRows() {
		UUID assetUuid = otherAsset(1).getUuid();
		dao().upsert(assetUuid, "ocr", "key-eng", "ocr", null, AssetNodeResult.STATE_SUCCESS, null, outputs("english"), null);
		dao().upsert(assetUuid, "ocr", "key-deu", "ocr", null, AssetNodeResult.STATE_SUCCESS, null, outputs("german"), null);
		dao().upsert(assetUuid, "whisper", "key-eng", "whisper", null, AssetNodeResult.STATE_SUCCESS, null, outputs("speech"), null);

		assertEquals(3, dao().listByAsset(assetUuid, null).size());
		List<AssetNodeOutput> ocr = dao().listByAsset(assetUuid, "ocr");
		assertEquals(2, ocr.size(), "The kind filter applies");
		assertTrue(ocr.stream().allMatch(o -> o.nodeKind().equals("ocr")));
		assertEquals("", ocr.get(0).producerVersion(), "A missing producer version is stored as the empty string");
	}

	/**
	 * Forgetting one kind on one asset must leave every other kind, and every other asset, alone - over-deleting here silently turns a cheap run into
	 * a full recomputation of the library.
	 */
	@Test
	public void testDeleteByAssetIsScoped() {
		UUID victim = otherAsset(2).getUuid();
		UUID bystander = otherAsset(3).getUuid();
		for (UUID assetUuid : List.of(victim, bystander)) {
			dao().upsert(assetUuid, "ocr", "key-a", "ocr", null, AssetNodeResult.STATE_SUCCESS, null, outputs("a"), null);
			dao().upsert(assetUuid, "whisper", "key-a", "whisper", null, AssetNodeResult.STATE_SUCCESS, null, outputs("b"), null);
		}

		assertEquals(1, dao().deleteByAsset(victim, "ocr"));
		assertNull(dao().loadByKey(victim, "ocr", "key-a"), "The requested kind is gone");
		assertNotNull(dao().loadByKey(victim, "whisper", "key-a"), "Another kind on the same asset survives");
		assertNotNull(dao().loadByKey(bystander, "ocr", "key-a"), "The same kind on another asset survives");

		assertEquals(1, dao().deleteByAsset(victim, null));
		assertTrue(dao().listByAsset(victim, null).isEmpty(), "Without a kind everything of the asset is gone");
		assertEquals(2, dao().listByAsset(bystander, null).size(), "The other asset keeps both rows");
	}

	/** A pruned run detaches the outputs it wrote instead of taking them with it. */
	@Test
	public void testRunDeleteDetaches() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 7101);
		UUID assetUuid = otherAsset(4).getUuid();
		dao().upsert(assetUuid, "whisper", "key-run", "whisper", null, AssetNodeResult.STATE_SUCCESS, null, outputs("x"), run.getUuid());
		assertEquals(run.getUuid(), dao().loadByKey(assetUuid, "whisper", "key-run").runUuid());

		pipelineRunDao().delete(run.getUuid());

		AssetNodeOutput loaded = dao().loadByKey(assetUuid, "whisper", "key-run");
		assertNotNull(loaded, "The output outlives the run that wrote it");
		assertNull(loaded.runUuid());
	}

	/** Replaying a failure would make a transient outage permanent; the DAO refuses it before the CHECK does. */
	@Test
	public void testFailureIsRefused() {
		UUID assetUuid = asset().getUuid();
		assertThrows(IllegalArgumentException.class,
			() -> dao().upsert(assetUuid, "whisper", "key-failed", "whisper", null, AssetNodeResult.STATE_FAILED, "boom", outputs("x"), null));
		assertNull(dao().loadByKey(assetUuid, "whisper", "key-failed"));
	}
}
