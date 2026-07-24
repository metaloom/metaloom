package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.vertx.core.json.JsonObject;

/**
 * Covers per-track transcription: worked cases 2 and 3.
 */
public class AssetTranscriptCompDaoTest extends AbstractJooqTest {

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
	public void testTranscriptPerAudioTrack() {
		UUID assetUuid = assetUuid();

		AssetAudioComp deTrack = dao().createAudioComp(userUuid(), assetUuid, "tika");
		deTrack.setStreamIndex(0).setLang("de");
		dao().upsertAudioComp(deTrack);

		AssetAudioComp enTrack = dao().createAudioComp(userUuid(), assetUuid, "tika");
		enTrack.setStreamIndex(1).setLang("en");
		dao().upsertAudioComp(enTrack);

		AssetTranscriptComp de = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		de.setStreamIndex(0).setLang("de").setAudioCompUuid(deTrack.getUuid()).setTranscriptText("Hallo Welt");
		dao().upsertTranscriptComp(de);

		AssetTranscriptComp en = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		en.setStreamIndex(1).setLang("en").setAudioCompUuid(enTrack.getUuid()).setTranscriptText("Hello world");
		dao().upsertTranscriptComp(en);

		assertEquals(2, dao().loadTranscriptComps(assetUuid).size());
		assertEquals("Hallo Welt", dao().loadTranscriptComp(assetUuid, "whisper", 0, "de").getTranscriptText());
		assertEquals(deTrack.getUuid(), dao().loadTranscriptComp(assetUuid, "whisper", 0, "de").getAudioCompUuid());
	}

	/**
	 * The same track transcribed into two languages: the language is part of the identity, so both survive.
	 */
	@Test
	public void testTwoLanguagesOfOneTrack() {
		UUID assetUuid = assetUuid();

		AssetTranscriptComp original = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		original.setStreamIndex(0).setLang("de").setTranscriptText("Hallo Welt");
		dao().upsertTranscriptComp(original);

		AssetTranscriptComp translated = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		translated.setStreamIndex(0).setLang("en").setTranscriptText("Hello world");
		dao().upsertTranscriptComp(translated);

		assertEquals(2, dao().loadTranscriptComps(assetUuid).size());
	}

	/**
	 * Worked case 3: re-transcribing with a newer model replaces the row and rewrites the producer version.
	 */
	@Test
	public void testModelUpgradeReplacesInPlace() {
		UUID assetUuid = assetUuid();

		AssetTranscriptComp small = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		small.setStreamIndex(0).setLang("de").setModel("small").setProducerVersion("whisper-small");
		small.setTranscriptText("Halo Welt");
		small.setDuration(5000L);
		dao().upsertTranscriptComp(small);

		AssetTranscriptComp large = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		large.setStreamIndex(0).setLang("de").setModel("large-v3").setProducerVersion("whisper-large-v3");
		large.setTranscriptText("Hallo Welt");
		large.setDuration(5000L);
		large.setTranscriptJson(new JsonObject().put("sections", new io.vertx.core.json.JsonArray()));
		dao().upsertTranscriptComp(large);

		assertEquals(small.getUuid(), large.getUuid(), "The upsert must reuse the existing row");
		assertEquals(1, dao().loadTranscriptComps(assetUuid).size());

		AssetTranscriptComp loaded = dao().loadTranscriptComp(assetUuid, "whisper", 0, "de");
		assertEquals("Hallo Welt", loaded.getTranscriptText());
		assertEquals("whisper-large-v3", loaded.getProducerVersion());
		assertEquals(5000L, loaded.getDuration());
		assertNotNull(loaded.getTranscriptJson());
	}

	/**
	 * Replacing the audio component must not take the transcript with it - the FK is ON DELETE SET NULL.
	 */
	@Test
	public void testDeletingTheAudioComponentKeepsTheTranscript() {
		UUID assetUuid = assetUuid();

		AssetAudioComp track = dao().createAudioComp(userUuid(), assetUuid, "tika");
		track.setStreamIndex(0);
		dao().upsertAudioComp(track);

		AssetTranscriptComp transcript = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		transcript.setStreamIndex(0).setLang("de").setAudioCompUuid(track.getUuid()).setTranscriptText("Hallo");
		dao().upsertTranscriptComp(transcript);

		dao().deleteAudioComp(track.getUuid());

		AssetTranscriptComp loaded = dao().loadTranscriptComp(transcript.getUuid());
		assertNotNull(loaded, "The transcript must survive its audio component");
		assertNull(loaded.getAudioCompUuid());
		assertEquals("Hallo", loaded.getTranscriptText());
	}

	/**
	 * An audio-only asset may be transcribed before any audio component exists, which is why the stream index rather than the FK is in the identity.
	 */
	@Test
	public void testTranscriptWithoutAudioComponent() {
		UUID assetUuid = assetUuid();

		AssetTranscriptComp comp = dao().createTranscriptComp(userUuid(), assetUuid, "whisper");
		comp.setTranscriptText("standalone");
		dao().upsertTranscriptComp(comp);

		AssetTranscriptComp loaded = dao().loadTranscriptComp(assetUuid, "whisper", 0, "");
		assertNotNull(loaded);
		assertNull(loaded.getAudioCompUuid());
		assertEquals("", loaded.getLang());
	}
}
