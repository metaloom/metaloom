package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.asset.AssetNodeResultDao;
import io.vertx.core.json.JsonObject;

/**
 * Covers the per-asset processing ledger: the short circuit, the invalidation sweep and the replace-in-place contract.
 */
public class AssetNodeResultDaoTest extends AbstractJooqTest {

	private AssetNodeResultDao dao() {
		return daos().assetNodeResultDao();
	}

	private UUID assetUuid() {
		return asset().getUuid();
	}

	private AssetNodeResult result(String nodeKind, String nodeId, String state, String version) {
		AssetNodeResult result = dao().createNodeResult(dummyUser().getUuid(), assetUuid(), nodeKind, nodeId);
		result.setState(state);
		result.setProducerVersion(version);
		return result;
	}

	@Test
	public void testUpsertAndLoadByNode() {
		UUID assetUuid = assetUuid();

		AssetNodeResult stored = result("whisper", "whisper-1", AssetNodeResult.STATE_SUCCESS, "large-v3");
		stored.setOrigin(AssetNodeResult.ORIGIN_COMPUTED);
		stored.setStarted(Instant.now());
		stored.setFinished(Instant.now());
		stored.setDurationMs(4200L);
		stored.setResultRef(new JsonObject().put("table", "asset_transcript_comp"));
		dao().upsert(stored);

		AssetNodeResult loaded = dao().loadByNode(assetUuid, "whisper", "whisper-1");
		assertNotNull(loaded, "The ledger answers whether the node has already run");
		assertEquals(AssetNodeResult.STATE_SUCCESS, loaded.getState());
		assertEquals(AssetNodeResult.ORIGIN_COMPUTED, loaded.getOrigin());
		assertEquals("large-v3", loaded.getProducerVersion());
		assertEquals(4200L, loaded.getDurationMs());
		assertEquals("asset_transcript_comp", loaded.getResultRef().getString("table"));
	}

	@Test
	public void testLoadByNodeUnknown() {
		assertNull(dao().loadByNode(assetUuid(), "whisper", "never-ran"));
	}

	@Test
	public void testRerunReplacesTheLedgerRow() {
		UUID assetUuid = assetUuid();

		AssetNodeResult first = result("facedetect", "", AssetNodeResult.STATE_FAILED, "v1");
		first.setReason("out of memory");
		dao().upsert(first);

		AssetNodeResult second = result("facedetect", "", AssetNodeResult.STATE_SUCCESS, "v2");
		dao().upsert(second);

		assertEquals(first.getUuid(), second.getUuid(), "The upsert must reuse the existing row");
		assertEquals(1, dao().loadByAsset(assetUuid).size());

		AssetNodeResult loaded = dao().loadByNode(assetUuid, "facedetect", "");
		assertEquals(AssetNodeResult.STATE_SUCCESS, loaded.getState());
		assertEquals("v2", loaded.getProducerVersion());
		assertNull(loaded.getReason());
	}

	@Test
	public void testLoadByAssetCoversEveryNode() {
		UUID assetUuid = assetUuid();

		dao().upsert(result("whisper", "whisper-1", AssetNodeResult.STATE_SUCCESS, "large-v3"));
		dao().upsert(result("facedetect", "facedetect-1", AssetNodeResult.STATE_SKIPPED, "v2"));
		dao().upsert(result("ocr", "ocr-1", AssetNodeResult.STATE_FAILED, "tesseract-5"));

		List<AssetNodeResult> results = dao().loadByAsset(assetUuid);
		assertEquals(3, results.size());
	}

	/**
	 * The invalidation sweep after a model upgrade.
	 */
	@Test
	public void testFindStale() {
		dao().upsert(result("facedetect", "a", AssetNodeResult.STATE_SUCCESS, "v1"));
		dao().upsert(result("facedetect", "b", AssetNodeResult.STATE_SUCCESS, "v2"));
		dao().upsert(result("whisper", "c", AssetNodeResult.STATE_SUCCESS, "v1"));

		List<AssetNodeResult> stale = dao().findStale("facedetect", "v2");
		assertEquals(1, stale.size());
		assertEquals("a", stale.get(0).getNodeId());

		assertTrue(dao().findStale("facedetect", "v9").size() == 2);
	}

	@Test
	public void testInvalidStateIsRejected() {
		AssetNodeResult invalid = result("whisper", "whisper-1", "MAYBE", "v1");
		assertThrows(DataAccessException.class, () -> dao().upsert(invalid));
	}

	@Test
	public void testMachineWrittenLedgerRow() {
		UUID assetUuid = assetUuid();

		AssetNodeResult result = dao().createNodeResult(null, assetUuid, "quality", "quality-1");
		result.setState(AssetNodeResult.STATE_SUCCESS);
		dao().upsert(result);

		AssetNodeResult loaded = dao().loadByNode(assetUuid, "quality", "quality-1");
		assertNotNull(loaded);
		assertNull(loaded.getCreatorUuid());
	}

	@Test
	public void testNodeIdDefaultsToEmptyString() {
		UUID assetUuid = assetUuid();

		AssetNodeResult result = dao().createNodeResult(null, assetUuid, "hash", null);
		result.setState(AssetNodeResult.STATE_SUCCESS);
		dao().upsert(result);

		assertNotNull(dao().loadByNode(assetUuid, "hash", null));
		assertNotNull(dao().loadByNode(assetUuid, "hash", ""));
	}
}
