package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.search.HexFingerprint;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.db.model.asset.AssetSegmentComp;

/**
 * Covers the two component tables added for node results that previously had nowhere to go: windowed perceptual fingerprints (worked case 13) and time ranged
 * segments (worked cases 8 and 9).
 */
public class AssetFingerprintSegmentCompDaoTest extends AbstractJooqTest {

	private AssetComponentDao dao() {
		return assetComponentDao();
	}

	private UUID assetUuid() {
		return asset().getUuid();
	}

	private UUID userUuid() {
		return dummyUser().getUuid();
	}

	@Test
	public void testWindowedFingerprint() {
		UUID assetUuid = assetUuid();

		for (int window = 0; window < 8; window++) {
			AssetFingerprintComp comp = dao().createFingerprintComp(userUuid(), assetUuid, "fingerprint");
			comp.setAlgorithm("metaloom-multisector-v1");
			comp.setWindowIndex(window);
			comp.setTimeFrom(window * 1000L).setTimeTo((window + 1) * 1000L);
			comp.setFingerprint("fp-" + window);
			dao().upsertFingerprintComp(comp);
		}

		assertEquals(8, dao().loadFingerprintComps(assetUuid).size());
		assertEquals("fp-3", dao().loadFingerprintComp(assetUuid, "fingerprint", "metaloom-multisector-v1", 3).getFingerprint());
	}

	@Test
	public void testFingerprintRerunUpsertsEachWindow() {
		UUID assetUuid = assetUuid();

		for (int run = 0; run < 2; run++) {
			for (int window = 0; window < 4; window++) {
				AssetFingerprintComp comp = dao().createFingerprintComp(userUuid(), assetUuid, "fingerprint");
				comp.setAlgorithm("v1").setWindowIndex(window).setFingerprint("run" + run + "-" + window);
				dao().upsertFingerprintComp(comp);
			}
		}

		assertEquals(4, dao().loadFingerprintComps(assetUuid).size(), "A second run must not double the window rows");
		assertEquals("run1-2", dao().loadFingerprintComp(assetUuid, "fingerprint", "v1", 2).getFingerprint());
	}

	/**
	 * The dedup lookup: given a fingerprint, find every asset that carries it.
	 */
	@Test
	public void testLookupByFingerprint() {
		UUID assetUuid = assetUuid();

		AssetFingerprintComp comp = dao().createFingerprintComp(userUuid(), assetUuid, "fingerprint");
		comp.setAlgorithm("v1").setWindowIndex(0).setFingerprint("deadbeef");
		dao().upsertFingerprintComp(comp);

		List<AssetFingerprintComp> hits = dao().findByFingerprint("v1", "deadbeef");
		assertEquals(1, hits.size());
		assertEquals(assetUuid, hits.get(0).getAssetUuid());

		assertEquals(0, dao().findByFingerprint("v1", "cafebabe").size());
	}

	/**
	 * The rebuild projection: {@code asset_fingerprint_comp} carries no content hash, so the similarity index would index nulls without this join.
	 */
	@Test
	public void testHexFingerprintProjectionJoinsTheAssetHash() {
		UUID assetUuid = assetUuid();
		// The pooled database is pre-populated, so the query is scoped by an algorithm only this test writes.
		String algorithm = "hex-projection-" + UUID.randomUUID();

		AssetFingerprintComp comp = dao().createFingerprintComp(userUuid(), assetUuid, "fingerprint");
		comp.setAlgorithm(algorithm).setWindowIndex(0).setFingerprint("deadbeef");
		dao().upsertFingerprintComp(comp);

		List<HexFingerprint> fingerprints;
		try (Stream<HexFingerprint> stream = dao().streamHexFingerprintsByAlgorithm(algorithm)) {
			fingerprints = stream.toList();
		}

		assertEquals(1, fingerprints.size());
		HexFingerprint fingerprint = fingerprints.get(0);
		assertEquals(assetUuid, fingerprint.assetUuid());
		assertEquals(algorithm, fingerprint.algorithm());
		assertEquals("deadbeef", fingerprint.fingerprint());
		assertEquals(asset().getSHA512().toString(), fingerprint.sha512(), "The hash has to come from the joined asset row");
	}

	@Test
	public void testScenesAndChaptersCoexist() {
		UUID assetUuid = assetUuid();

		dao().replaceSegmentComps(assetUuid, "scene-detection", "SCENE", segments(12));
		dao().replaceSegmentComps(assetUuid, "llm", "CHAPTER", segments(3));

		assertEquals(15, dao().loadSegmentComps(assetUuid).size());
		assertEquals(12, dao().loadSegmentComps(assetUuid, "scene-detection", "SCENE").size());
		assertEquals(3, dao().loadSegmentComps(assetUuid, "llm", "CHAPTER").size());
	}

	/**
	 * The one write path that is not a single upsert: a re-run producing fewer segments must not leave the tail of the previous run behind.
	 */
	@Test
	public void testShorterRerunDropsTheTail() {
		UUID assetUuid = assetUuid();

		dao().replaceSegmentComps(assetUuid, "scene-detection", "SCENE", segments(10));
		assertEquals(10, dao().loadSegmentComps(assetUuid, "scene-detection", "SCENE").size());

		dao().replaceSegmentComps(assetUuid, "scene-detection", "SCENE", segments(4));
		List<AssetSegmentComp> remaining = dao().loadSegmentComps(assetUuid, "scene-detection", "SCENE");
		assertEquals(4, remaining.size());
		assertEquals(0, remaining.get(0).getSeq());
		assertEquals(3, remaining.get(3).getSeq());
	}

	@Test
	public void testSegmentTimelineLookup() {
		UUID assetUuid = assetUuid();
		dao().replaceSegmentComps(assetUuid, "scene-detection", "SCENE", segments(5));

		List<AssetSegmentComp> scenes = dao().loadSegmentComps(assetUuid, "scene-detection", "SCENE");
		AssetSegmentComp atTwoAndAHalfSeconds = scenes.stream()
			.filter(s -> s.getTimeFrom() <= 2500 && s.getTimeTo() >= 2500)
			.findFirst()
			.orElse(null);
		assertNotNull(atTwoAndAHalfSeconds);
		assertEquals(2, atTwoAndAHalfSeconds.getSeq());
	}

	@Test
	public void testInvalidSegmentTypeIsRejected() {
		UUID assetUuid = assetUuid();

		AssetSegmentComp comp = dao().createSegmentComp(userUuid(), assetUuid, "scene-detection");
		comp.setSegmentType("NOT_A_KIND").setSeq(0).setTimeFrom(0).setTimeTo(1000);
		assertThrows(DataAccessException.class, () -> dao().storeSegmentComp(comp));
	}

	@Test
	public void testInvertedRangeIsRejected() {
		UUID assetUuid = assetUuid();

		AssetSegmentComp comp = dao().createSegmentComp(userUuid(), assetUuid, "scene-detection");
		comp.setSegmentType("SCENE").setSeq(0).setTimeFrom(5000).setTimeTo(1000);
		assertThrows(DataAccessException.class, () -> dao().storeSegmentComp(comp));
	}

	private List<AssetSegmentComp> segments(int count) {
		UUID assetUuid = assetUuid();
		List<AssetSegmentComp> comps = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			AssetSegmentComp comp = dao().createSegmentComp(userUuid(), assetUuid, "unused");
			comp.setTimeFrom(i * 1000L).setTimeTo((i + 1) * 1000L).setScore(0.5f);
			comps.add(comp);
		}
		return comps;
	}
}
