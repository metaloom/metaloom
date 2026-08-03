package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.user.User;

/**
 * Covers the identity contract of the typed component tables: the discriminators that must allow several components per asset, and the unique key that
 * must reject a duplicate.
 *
 * <p>
 * Each test is one of the worked cases in spec/features/db/DATABASE_TASKS.md.
 * </p>
 */
public class AssetComponentKeyTest extends AbstractJooqTest {

	private AssetComponentDao dao() {
		return assetComponentDao();
	}

	private UUID assetUuid() {
		Asset asset = asset();
		return asset.getUuid();
	}

	private UUID userUuid() {
		User user = dummyUser();
		return user.getUuid();
	}

	/**
	 * Worked case 1: a video with a German and an English audio track.
	 */
	@Test
	public void testTwoAudioTracksCoexist() {
		UUID assetUuid = assetUuid();

		AssetAudioComp de = dao().createAudioComp(userUuid(), assetUuid, "tika");
		de.setStreamIndex(0).setLang("de").setAudioChannels(2);
		dao().upsertAudioComp(de);

		AssetAudioComp en = dao().createAudioComp(userUuid(), assetUuid, "tika");
		en.setStreamIndex(1).setLang("en").setAudioChannels(6);
		dao().upsertAudioComp(en);

		assertEquals(2, dao().loadAudioComps(assetUuid).size());
		assertEquals("de", dao().loadAudioComp(assetUuid, "tika", 0).getLang());
		assertEquals(6, dao().loadAudioComp(assetUuid, "tika", 1).getAudioChannels());
	}

	@Test
	public void testDuplicateAudioTrackIsRejected() {
		UUID assetUuid = assetUuid();

		AssetAudioComp first = dao().createAudioComp(userUuid(), assetUuid, "tika");
		first.setStreamIndex(0);
		dao().storeAudioComp(first);

		AssetAudioComp duplicate = dao().createAudioComp(userUuid(), assetUuid, "tika");
		duplicate.setStreamIndex(0);
		assertThrows(DataAccessException.class, () -> dao().storeAudioComp(duplicate));
	}

	/**
	 * Worked case 16: two producers of the same dimension yield two partially filled components, discriminated by node kind. The read side coalesces
	 * them; the write side must never merge.
	 */
	@Test
	public void testTwoProducersOfTheSameDimensionCoexist() {
		UUID assetUuid = assetUuid();

		AssetVideoComp probed = dao().createVideoComp(userUuid(), assetUuid, "tika");
		probed.setMediaWidth(1920).setMediaHeight(1080).setVideoEncoding("h264");
		dao().upsertVideoComp(probed);

		AssetVideoComp measured = dao().createVideoComp(userUuid(), assetUuid, "quality");
		measured.setFps(29.97f).setFrameCount(51000L).setBlurriness(12.5f);
		dao().upsertVideoComp(measured);

		assertEquals(2, dao().loadVideoComps(assetUuid).size());
		assertEquals(1920, dao().loadVideoComp(assetUuid, "tika", 0).getMediaWidth());
		assertNull(dao().loadVideoComp(assetUuid, "tika", 0).getFps());
		assertEquals(29.97f, dao().loadVideoComp(assetUuid, "quality", 0).getFps(), 0.001f);
	}

	/**
	 * Worked case 6: Tika writes the whole document as page 0, OCR writes one component per page.
	 */
	@Test
	public void testWholeDocumentAndPerPageTextCoexist() {
		UUID assetUuid = assetUuid();

		AssetDocComp whole = dao().createDocComp(userUuid(), assetUuid, "tika");
		whole.setPageNumber(0).setPageCount(3).setDocPlainText("everything");
		dao().upsertDocComp(whole);

		for (int page = 1; page <= 3; page++) {
			AssetDocComp ocr = dao().createDocComp(userUuid(), assetUuid, "ocr");
			ocr.setPageNumber(page).setDocPlainText("page " + page).setTextLang("en");
			dao().upsertDocComp(ocr);
		}

		assertEquals(4, dao().loadDocComps(assetUuid).size());
		assertEquals("everything", dao().loadDocComp(assetUuid, "tika", 0).getDocPlainText());
		assertEquals("page 2", dao().loadDocComp(assetUuid, "ocr", 2).getDocPlainText());
	}

	/**
	 * Worked case 4 and 5: a GPS track is one component per time offset, and an EXIF position coexists with an LLM guess.
	 */
	@Test
	public void testGeoTrackAndCompetingProducersCoexist() {
		UUID assetUuid = assetUuid();

		for (long offset : new long[] { 0L, 1000L, 2000L }) {
			AssetGeoComp point = dao().createGeoComp(userUuid(), assetUuid, "tika");
			point.setMethod("gps-track").setTimeFrom(offset).setGeoLat(48.2 + offset / 100000d).setGeoLon(16.3);
			dao().upsertGeoComp(point);
		}

		AssetGeoComp guess = dao().createGeoComp(userUuid(), assetUuid, "llm");
		guess.setMethod("llm").setGeoAlias("Vienna").setConfidence(0.6f);
		dao().upsertGeoComp(guess);

		assertEquals(4, dao().loadGeoComps(assetUuid).size());
		assertNotNull(dao().loadGeoComp(assetUuid, "tika", "gps-track", 2000L));
		assertEquals("Vienna", dao().loadGeoComp(assetUuid, "llm", "llm", 0L).getGeoAlias());
	}

	/**
	 * A node re-run must leave exactly one row per identity. This is what {@code POST /assets/:uuid/components} routes through, and what makes an
	 * ingest pipeline re-runnable at all: the insert path would violate the unique key on the second pass.
	 */
	@Test
	public void testUpsertGeoCompReplacesInPlace() {
		UUID assetUuid = assetUuid();

		AssetGeoComp first = dao().createGeoComp(userUuid(), assetUuid, "metadata");
		first.setMethod("exif").setTimeFrom(0L).setGeoLat(35.360833).setGeoLon(138.7275).setAccuracyM(12f);
		dao().upsertGeoComp(first);

		AssetGeoComp rerun = dao().createGeoComp(userUuid(), assetUuid, "metadata");
		rerun.setMethod("exif").setTimeFrom(0L).setGeoLat(35.36).setGeoLon(138.73).setGeoAlias("Fujinomiya, JP");
		dao().upsertGeoComp(rerun);

		assertEquals(1, dao().loadGeoComps(assetUuid).size(), "a re-run must replace its own row, not append one");
		AssetGeoComp loaded = dao().loadGeoComp(assetUuid, "metadata", "exif", 0L);
		assertEquals(35.36, loaded.getGeoLat(), 0.0001);
		assertEquals("Fujinomiya, JP", loaded.getGeoAlias());
		// Everything the node computed is overwritten, including a value the second run left unset.
		assertNull(loaded.getAccuracyM());
	}

	/**
	 * The same coordinate read out of two standards is two answers to "what does this file say", and the method discriminates them.
	 */
	@Test
	public void testTheSameNodeKindCanRecordOnePositionPerSource() {
		UUID assetUuid = assetUuid();

		AssetGeoComp exif = dao().createGeoComp(userUuid(), assetUuid, "metadata");
		exif.setMethod("exif").setTimeFrom(0L).setGeoLat(35.360833).setGeoLon(138.7275);
		dao().upsertGeoComp(exif);

		AssetGeoComp sidecar = dao().createGeoComp(userUuid(), assetUuid, "metadata");
		sidecar.setMethod("sidecar").setTimeFrom(0L).setGeoLat(35.4).setGeoLon(138.7);
		dao().upsertGeoComp(sidecar);

		assertEquals(2, dao().loadGeoComps(assetUuid).size());
		assertNotNull(dao().loadGeoComp(assetUuid, "metadata", "sidecar", 0L));
	}

	/**
	 * Worked case 7: an audio asset legitimately owns an image component (embedded cover art). Component writes are never gated on the asset's mime
	 * type.
	 */
	@Test
	public void testImageComponentOnAnyAsset() {
		UUID assetUuid = assetUuid();

		AssetImageComp cover = dao().createImageComp(userUuid(), assetUuid, "tika");
		cover.setStreamIndex(1).setMediaWidth(600).setMediaHeight(600).setImageEncoding("jpeg");
		dao().upsertImageComp(cover);

		AssetImageComp loaded = dao().loadImageComp(assetUuid, "tika", 1);
		assertNotNull(loaded);
		assertEquals(600, loaded.getMediaWidth());
		assertEquals("jpeg", loaded.getImageEncoding());
	}

	@Test
	public void testProvenanceRoundTrips() {
		UUID assetUuid = assetUuid();

		AssetImageComp comp = dao().createImageComp(userUuid(), assetUuid, "quality");
		comp.setNodeId("quality-1");
		comp.setProducerVersion("laplacian-v2");
		comp.setConfidence(0.8f);
		comp.setBlurriness(3.25f);
		dao().upsertImageComp(comp);

		AssetImageComp loaded = dao().loadImageComp(comp.getUuid());
		assertEquals("quality", loaded.getNodeKind());
		assertEquals("quality-1", loaded.getNodeId());
		assertEquals("laplacian-v2", loaded.getProducerVersion());
		assertEquals(0.8f, loaded.getConfidence(), 0.001f);
		assertEquals(3.25f, loaded.getBlurriness(), 0.001f);
	}

	@Test
	public void testMachineWrittenRowsAcceptNullAudit() {
		UUID assetUuid = assetUuid();

		AssetVideoComp comp = dao().createVideoComp(null, assetUuid, "quality");
		comp.setFps(25f);
		dao().upsertVideoComp(comp);

		AssetVideoComp loaded = dao().loadVideoComp(comp.getUuid());
		assertNotNull(loaded);
		assertNull(loaded.getCreatorUuid());
		assertNull(loaded.getEditorUuid());
	}
}
