package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET_BINARY;
import static io.metaloom.loom.db.model.perm.Permission.READ_CLUSTER;
import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PERSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterConfirmRequest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterMemberCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterMemberModel;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonHeroVoiceRequest;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonMatchResponse;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.metaloom.loom.rest.model.person.PersonPrintResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleImportRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleResponse;

/**
 * Covers a person's voice - {@code /persons/:uuid/voice-samples}, {@code /persons/:uuid/hero-voice} and the prints a voice model stores against a
 * sample - and person matching, {@code /person-match}, plus the audio clip a reviewer listens to, {@code /assets/:uuid/audio-clip}.
 *
 * <p>
 * The tests that cut audio are skipped without ffmpeg, which is optional by design; everything decided before ffmpeg is consulted - permissions,
 * validation, ownership - runs everywhere.
 * </p>
 */
public class PersonVoiceEndpointTest extends AbstractEndpointTest {

	private static final String MODEL = "test-voice-model";

	private final Path storageDir;

	public PersonVoiceEndpointTest() throws IOException {
		this.storageDir = Files.createTempDirectory("loom-voice-test");
		loom.withOptions(o -> {
			o.getStorage().setUploadDirectory(storageDir.toString());
			o.getMedia().setCachePath(storageDir.resolve("media-cache").toString());
		});
	}

	@Test
	public void testUploadedVoiceSampleIsListedAndDownloadable() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "voice-person");
			assertNull(person.getHeroVoiceUrl(), "a new person has no hero voice");

			PersonVoiceSampleResponse sample = uploadSample(client, person.getUuid(), 4);
			assertNotNull(sample.getUuid());
			assertEquals("audio/wav", sample.getMimeType());
			assertTrue(sample.getSize() > 44, "the stored size is the uploaded size");
			assertFalse(sample.isHero(), "uploading does not make a sample the hero - that is a separate decision");
			assertTrue(sample.getPrints().isEmpty(), "no model has embedded a fresh upload");
			assertNull(sample.getSourceAssetUuid(), "an upload has no provenance in an asset");
			assertEquals("/api/v1/persons/" + person.getUuid() + "/voice-samples/" + sample.getUuid() + "/data", sample.getUrl());
			if (ffmpegPresent()) {
				assertNotNull(sample.getDurationMs(), "the length is measured when ffprobe is there");
				assertEquals(4000L, sample.getDurationMs(), 50L);
			}

			var list = client.listPersonVoiceSamples(person.getUuid()).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals(sample.getUuid(), list.getData().get(0).getUuid());
			assertEquals(0, client.listPersonImages(person.getUuid()).sync().body().getData().size(), "a voice is not a picture");

			try (var response = client.downloadPersonVoiceSample(person.getUuid(), sample.getUuid()).sync().body()) {
				assertEquals(200, response.code());
				assertEquals(sample.getSize(), response.getStream().readAllBytes().length);
				assertEquals("audio/wav", response.getContentType());
			}
		}
	}

	/** A sample is handed to a voice model; a picture would only fail there, later and less clearly. */
	@Test
	public void testUploadRefusesWhatIsNotAudio() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "picture-not-voice");
			File picture = File.createTempFile("not-a-voice-", ".txt");
			picture.deleteOnExit();
			Files.writeString(picture.toPath(), "hello");
			expect(415, "Unsupported Media Type", client.uploadPersonVoiceSample(person.getUuid(), picture, "text/plain", null));
			assertEquals(0, client.listPersonVoiceSamples(person.getUuid()).sync().body().getData().size());
		}
	}

	@Test
	public void testHeroVoiceRoundTripsAndDeletingItLeavesThePersonStanding() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "hero-voice-person");
			PersonVoiceSampleResponse sample = uploadSample(client, person.getUuid(), 2);

			PersonResponse withHero = client.setPersonHeroVoice(person.getUuid(), new PersonHeroVoiceRequest()
				.setSampleUuid(sample.getUuid().toString())).sync().body();
			assertEquals(sample.getUrl(), withHero.getHeroVoiceUrl());
			assertEquals(sample.getUrl(), client.loadPerson(person.getUuid()).sync().body().getHeroVoiceUrl(), "and it survives a reload");
			assertTrue(client.listPersonVoiceSamples(person.getUuid()).sync().body().getData().get(0).isHero());

			PersonResponse cleared = client.setPersonHeroVoice(person.getUuid(), new PersonHeroVoiceRequest()).sync().body();
			assertNull(cleared.getHeroVoiceUrl(), "a blank sampleUuid clears it");

			client.setPersonHeroVoice(person.getUuid(), new PersonHeroVoiceRequest().setSampleUuid(sample.getUuid().toString())).sync().body();
			client.deletePersonVoiceSample(person.getUuid(), sample.getUuid()).sync().body();
			PersonResponse reloaded = client.loadPerson(person.getUuid()).sync().body();
			assertNotNull(reloaded, "the person survives losing their hero sample");
			assertNull(reloaded.getHeroVoiceUrl());
		}
	}

	/** Answered as missing, not forbidden: the pairing is part of the address. */
	@Test
	public void testASampleIsNotAddressableThroughAnotherPerson() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse owner = createPerson(client, "voice-owner");
			PersonResponse stranger = createPerson(client, "voice-stranger");
			PersonVoiceSampleResponse sample = uploadSample(client, owner.getUuid(), 2);

			expect(404, "Not Found", client.downloadPersonVoiceSample(stranger.getUuid(), sample.getUuid()));
			expect(404, "Not Found", client.deletePersonVoiceSample(stranger.getUuid(), sample.getUuid()));
			expect(404, "Not Found", client.setPersonHeroVoice(stranger.getUuid(), new PersonHeroVoiceRequest()
				.setSampleUuid(sample.getUuid().toString())));
			expect(404, "Not Found", client.storePersonVoicePrint(stranger.getUuid(), sample.getUuid(), print(MODEL, 1f, 0f)));
			// A voice sample is not an image either, whichever person asks.
			expect(404, "Not Found", client.storePersonImagePrint(owner.getUuid(), sample.getUuid(), new PersonPrintCreateRequest()
				.setType("face").setModel(MODEL).setVector(new Float[] { 1f })));
		}
	}

	/**
	 * A model's verdict on a sample: stored, replaced on a re-run, listed without its vector, and refused when it names the wrong modality.
	 */
	@Test
	public void testPrintsAreStoredReplacedAndListed() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "print-person");
			PersonVoiceSampleResponse sample = uploadSample(client, person.getUuid(), 2);

			PersonPrintResponse failed = client.storePersonVoicePrint(person.getUuid(), sample.getUuid(), new PersonPrintCreateRequest()
				.setType("speaker").setModel(MODEL).setError("no speech found")).sync().body();
			assertFalse(failed.isUsable());
			assertEquals("no speech found", failed.getError());

			PersonPrintResponse usable = client.storePersonVoicePrint(person.getUuid(), sample.getUuid(), print(MODEL, 0.6f, 0.8f)).sync().body();
			assertTrue(usable.isUsable());
			assertEquals(failed.getUuid(), usable.getUuid(), "the same sample and model is one print, replaced");
			assertEquals(2, usable.getDimensions());

			PersonVoiceSampleResponse listed = client.listPersonVoiceSamples(person.getUuid()).sync().body().getData().get(0);
			assertEquals(1, listed.getPrints().size());
			assertEquals(MODEL, listed.getPrints().get(0).getModel());
			assertTrue(listed.getPrints().get(0).isUsable());

			expect(400, "Bad Request", client.storePersonVoicePrint(person.getUuid(), sample.getUuid(), new PersonPrintCreateRequest()
				.setType("face").setModel(MODEL).setVector(new Float[] { 1f, 0f })));
			expect(400, "Bad Request", client.storePersonVoicePrint(person.getUuid(), sample.getUuid(), new PersonPrintCreateRequest()
				.setType("speaker").setModel(MODEL)));
			expect(400, "Bad Request", client.storePersonVoicePrint(person.getUuid(), sample.getUuid(), new PersonPrintCreateRequest()
				.setType("speaker").setModel(MODEL).setVector(new Float[] { 1f }).setError("both")));
		}
	}

	/**
	 * Matching reads what reviewers confirmed and what the persons' own samples say, in one model's space, and names the people it found. It also
	 * hands back the samples that still need a print in that model - the work a node holding the model can do.
	 */
	@Test
	public void testMatchSuggestsFromConfirmedClustersAndSamples() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse anna = createPerson(client, "Anna Match");
			PersonResponse bert = createPerson(client, "Bert Match");

			// Anna: a speaker cluster a reviewer confirmed.
			AssetResponse asset = uploadSpeechAsset(client, 10);
			ClusterResponse cluster = speakerCluster(client, asset.getUuid(), new Float[] { 1f, 0f, 0f, 0f }, null);
			client.confirmCluster(cluster.getUuid(), new ClusterConfirmRequest().setPersonUuid(anna.getUuid().toString())).sync().body();

			// Bert: a sample of his own, enrolled in the model, plus one that is not yet.
			PersonVoiceSampleResponse enrolled = uploadSample(client, bert.getUuid(), 2);
			client.storePersonVoicePrint(bert.getUuid(), enrolled.getUuid(), print(MODEL, 0f, 1f, 0f, 0f)).sync().body();
			PersonVoiceSampleResponse waiting = uploadSample(client, bert.getUuid(), 3);

			PersonMatchRequest request = new PersonMatchRequest().setType("speaker").setModel(MODEL).setThreshold(0.5f);
			request.getVectors().add(new Float[] { 0.9f, 0.1f, 0f, 0f });
			request.getVectors().add(new Float[] { 0.1f, 0.9f, 0f, 0f });
			request.getVectors().add(new Float[] { 0f, 0f, 1f, 0f });
			PersonMatchResponse response = client.matchPersons(request).sync().body();

			assertEquals(2, response.getPersonCount());
			assertEquals(3, response.getResults().size());
			assertEquals(anna.getUuid().toString(), response.getResults().get(0).getCandidates().get(0).getPersonUuid());
			assertEquals("Anna Match", response.getResults().get(0).getCandidates().get(0).getName());
			assertEquals(bert.getUuid().toString(), response.getResults().get(1).getCandidates().get(0).getPersonUuid());
			assertTrue(response.getResults().get(2).getCandidates().isEmpty(), "a voice like nobody's is matched to nobody");
			assertTrue(response.getPendingSamples().stream().anyMatch(p -> p.getSampleUuid().equals(waiting.getUuid().toString())),
				"the unenrolled sample is offered as work");
			assertTrue(response.getPendingSamples().stream().noneMatch(p -> p.getSampleUuid().equals(enrolled.getUuid().toString())));

			// Another model shares no space with these references.
			PersonMatchRequest otherModel = new PersonMatchRequest().setType("speaker").setModel("another-model");
			otherModel.getVectors().add(new Float[] { 1f, 0f, 0f, 0f });
			PersonMatchResponse none = client.matchPersons(otherModel).sync().body();
			assertEquals(0, none.getPersonCount());
			assertTrue(none.getResults().get(0).getCandidates().isEmpty());
			assertTrue(none.getPendingSamples().size() >= 2, "every sample is still pending in a model that never saw it");

			PersonMatchRequest mixed = new PersonMatchRequest().setType("speaker").setModel(MODEL);
			mixed.getVectors().add(new Float[] { 1f, 0f });
			mixed.getVectors().add(new Float[] { 1f, 0f, 0f });
			expect(400, "Bad Request", client.matchPersons(mixed));
			expect(400, "Bad Request", client.matchPersons(new PersonMatchRequest().setType("speaker").setModel(MODEL)));
		}
	}

	/**
	 * A producer's suggestion is stored apart from the verdict, named in every response, and a person deleted between the match and the write is
	 * dropped rather than failing the write.
	 */
	@Test
	public void testBulkClustersCarryANamedSuggestion() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse suggested = createPerson(client, "Suggested Speaker");
			AssetResponse asset = uploadSpeechAsset(client, 10);

			ClusterResponse cluster = speakerCluster(client, asset.getUuid(), new Float[] { 1f, 0f, 0f, 0f }, suggested.getUuid());
			assertEquals(suggested.getUuid().toString(), cluster.getSuggestedPersonUuid());
			assertEquals("Suggested Speaker", cluster.getSuggestedPersonName());
			assertEquals(0.77f, cluster.getSuggestedScore(), 0.0001f);
			assertNull(cluster.getPersonUuid(), "a suggestion is not a verdict");
			assertEquals("PENDING", cluster.getReviewStatus());
			assertEquals(MODEL, cluster.getModel());

			ClusterResponse listed = client.listAssetClusters(asset.getUuid()).sync().body().getData().get(0);
			assertEquals("Suggested Speaker", listed.getSuggestedPersonName(), "the asset listing names the suggestion too");

			ClusterResponse confirmed = client.confirmCluster(cluster.getUuid(), new ClusterConfirmRequest()
				.setPersonUuid(suggested.getUuid().toString())).sync().body();
			assertEquals("Suggested Speaker", confirmed.getPersonName());

			ClusterResponse ghost = speakerCluster(client, asset.getUuid(), new Float[] { 0f, 1f, 0f, 0f }, UUID.randomUUID(), 1);
			assertNull(ghost.getSuggestedPersonUuid(), "a suggestion naming nobody is dropped, and the cluster is still written");
		}
	}

	/** A speaker's members carry where the voice was heard, which is what lets a reviewer listen to it. */
	@Test
	public void testSpeakerMembersCarryTheirTimeWindow() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadSpeechAsset(client, 10);
			ClusterResponse cluster = speakerCluster(client, asset.getUuid(), new Float[] { 1f, 0f, 0f, 0f }, null);
			List<ClusterMemberModel> members = client.listClusterMembers(cluster.getUuid()).sync().body().getMembers();
			assertEquals(3, members.size());
			assertEquals(0L, members.get(0).getTimeFrom());
			assertEquals(3000L, members.get(0).getTimeTo());
			assertEquals(6000L, members.get(2).getTimeFrom());
		}
	}

	/**
	 * Importing without a range takes the longest unbroken turn: the first two windows overlap into 0 - 4.5 s, the third stands alone at 6 - 9 s. The
	 * sample is a copy, so it outlives the asset.
	 */
	@Test
	public void testImportTakesTheLongestTurnAndOutlivesTheAsset() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to cut audio");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "imported-voice");
			AssetResponse asset = uploadSpeechAsset(client, 10);
			ClusterResponse cluster = speakerCluster(client, asset.getUuid(), new Float[] { 1f, 0f, 0f, 0f }, null);

			PersonVoiceSampleResponse sample = client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(cluster.getUuid().toString())).sync().body();
			assertEquals(0L, sample.getSourceTimeFrom());
			assertEquals(4500L, sample.getSourceTimeTo());
			assertEquals(4500L, sample.getDurationMs());
			assertEquals(asset.getUuid().toString(), sample.getSourceAssetUuid());
			assertEquals(cluster.getUuid().toString(), sample.getSourceClusterUuid());
			assertEquals("audio/wav", sample.getMimeType());
			// 4.5 s of 16 kHz mono 16-bit PCM plus a header.
			assertEquals(44 + 4500 * 32, sample.getSize(), 256);

			PersonVoiceSampleResponse ranged = client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(cluster.getUuid().toString()).setTimeFrom(6000L).setTimeTo(8000L)).sync().body();
			assertEquals(2000L, ranged.getDurationMs());

			client.deleteAsset(asset.getUuid()).sync().body();
			try (var response = client.downloadPersonVoiceSample(person.getUuid(), sample.getUuid()).sync().body()) {
				assertEquals(200, response.code(), "the sample belongs to the person, not to the asset");
				assertTrue(response.getStream().readAllBytes().length > 44);
			}
		}
	}

	@Test
	public void testImportValidation() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "import-validation");
			AssetResponse asset = uploadSpeechAsset(client, 10);
			ClusterResponse speaker = speakerCluster(client, asset.getUuid(), new Float[] { 1f, 0f, 0f, 0f }, null);

			expect(400, "Bad Request", client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(speaker.getUuid().toString()).setTimeFrom(1000L)));
			expect(400, "Bad Request", client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(speaker.getUuid().toString()).setTimeFrom(0L).setTimeTo(121_000L)));
			expect(400, "Bad Request", client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(speaker.getUuid().toString()).setTimeFrom(5000L).setTimeTo(5000L)));
			expect(404, "Not Found", client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(UUID.randomUUID().toString())));

			ClusterBulkCreateRequest faces = new ClusterBulkCreateRequest();
			faces.add(new ClusterCreateItem().setType("face").setNodeKind("facedetect").setClusterIndex(0));
			ClusterResponse face = client.bulkCreateAssetClusters(asset.getUuid(), faces).sync().body().getClusters().get(0);
			expect(400, "Bad Request", client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(face.getUuid().toString())));
		}
	}

	@Test
	public void testAudioClipIsAStretchOfTheAsset() throws Exception {
		Assumptions.assumeTrue(ffmpegPresent(), "ffmpeg is required to cut audio");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = uploadSpeechAsset(client, 10);
			try (var response = client.downloadAssetAudioClip(asset.getUuid(), 1000, 3000).sync().body()) {
				assertEquals(200, response.code());
				assertEquals("audio/wav", response.getContentType());
				assertEquals(44 + 2000 * 32, response.getStream().readAllBytes().length, 256);
			}
			expect(400, "Bad Request", client.downloadAssetAudioClip(asset.getUuid(), 3000, 1000));
			expect(400, "Bad Request", client.downloadAssetAudioClip(asset.getUuid(), 0, 121_000));
			expect(422, "Unprocessable Entity", client.downloadAssetAudioClip(asset.getUuid(), 60_000, 61_000));
		}
	}

	/**
	 * Reading a person's voice needs {@code READ_PERSON}, changing it {@code UPDATE_PERSON} - the person's own permissions. Cutting a sample out of an
	 * asset additionally needs {@code READ_CLUSTER} and {@code READ_ASSET_BINARY}, and matching names people so it needs {@code READ_PERSON}.
	 */
	@Test
	public void testVoiceFollowsThePersonPermissions() throws Exception {
		UUID personUuid;
		UUID sampleUuid;
		UUID assetUuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			personUuid = createPerson(admin, "perm-voice").getUuid();
			sampleUuid = uploadSample(admin, personUuid, 2).getUuid();
			assetUuid = uploadSpeechAsset(admin, 4).getUuid();
		}
		PersonMatchRequest match = new PersonMatchRequest().setType("speaker").setModel(MODEL);
		match.getVectors().add(new Float[] { 1f, 0f });
		PersonVoiceSampleImportRequest importRequest = new PersonVoiceSampleImportRequest().setClusterUuid(UUID.randomUUID().toString());

		try (LoomHttpClient nobody = loginPermissionlessClient()) {
			expect(403, "Forbidden", nobody.listPersonVoiceSamples(personUuid));
			expect(403, "Forbidden", nobody.downloadPersonVoiceSample(personUuid, sampleUuid));
			expect(403, "Forbidden", nobody.uploadPersonVoiceSample(personUuid, speechFile(1, 300), "audio/wav", null));
			expect(403, "Forbidden", nobody.deletePersonVoiceSample(personUuid, sampleUuid));
			expect(403, "Forbidden", nobody.setPersonHeroVoice(personUuid, new PersonHeroVoiceRequest()));
			expect(403, "Forbidden", nobody.storePersonVoicePrint(personUuid, sampleUuid, print(MODEL, 1f, 0f)));
			expect(403, "Forbidden", nobody.importPersonVoiceSample(personUuid, importRequest));
			expect(403, "Forbidden", nobody.matchPersons(match));
			expect(403, "Forbidden", nobody.downloadAssetAudioClip(assetUuid, 0, 1000));
		}

		try (LoomHttpClient reader = loginClientWith("voice-reader", READ_PERSON)) {
			assertEquals(1, reader.listPersonVoiceSamples(personUuid).sync().body().getData().size(), "READ_PERSON is enough to look");
			try (var response = reader.downloadPersonVoiceSample(personUuid, sampleUuid).sync().body()) {
				assertEquals(200, response.code(), "and to listen");
			}
			assertNotNull(reader.matchPersons(match).sync().body(), "and to ask who a voice resembles");
			expect(403, "Forbidden", reader.setPersonHeroVoice(personUuid, new PersonHeroVoiceRequest()));
			expect(403, "Forbidden", reader.deletePersonVoiceSample(personUuid, sampleUuid));
			expect(403, "Forbidden", reader.storePersonVoicePrint(personUuid, sampleUuid, print(MODEL, 1f, 0f)));
		}

		try (LoomHttpClient writer = loginClientWith("voice-writer", READ_PERSON, UPDATE_PERSON)) {
			assertNotNull(writer.uploadPersonVoiceSample(personUuid, speechFile(1, 310), "audio/wav", null).sync().body());
			assertTrue(writer.storePersonVoicePrint(personUuid, sampleUuid, print(MODEL, 1f, 0f)).sync().body().isUsable());
			assertNotNull(writer.setPersonHeroVoice(personUuid, new PersonHeroVoiceRequest().setSampleUuid(sampleUuid.toString())).sync().body()
				.getHeroVoiceUrl(), "UPDATE_PERSON may choose the hero sample");
			// Cutting a voice out of an asset reads the asset's audio: editing a person is not on its own permission to do that.
			expect(403, "Forbidden", writer.importPersonVoiceSample(personUuid, importRequest));
		}

		try (LoomHttpClient importer = loginClientWith("voice-importer", READ_PERSON, UPDATE_PERSON, READ_CLUSTER, READ_ASSET_BINARY)) {
			// Past the permission gate: the cluster does not exist.
			expect(404, "Not Found", importer.importPersonVoiceSample(personUuid, importRequest));
		}
	}

	// ---------------------------------------------------------------------------------------------

	private PersonResponse createPerson(LoomHttpClient client, String alias) throws LoomClientException {
		return client.createPerson(new PersonCreateRequest().setAlias(alias)).sync().body();
	}

	private PersonVoiceSampleResponse uploadSample(LoomHttpClient client, UUID personUuid, int seconds) throws Exception {
		return client.uploadPersonVoiceSample(personUuid, speechFile(seconds, 220 + (int) (Math.random() * 400)), "audio/wav", null).sync().body();
	}

	private AssetResponse uploadSpeechAsset(LoomHttpClient client, int seconds) throws Exception {
		return client.uploadAsset(speechFile(seconds, 180 + (int) (Math.random() * 400)), LIBRARY_UUID, "audio/wav").sync().body();
	}

	private static PersonPrintCreateRequest print(String model, Float... vector) {
		return new PersonPrintCreateRequest().setType("speaker").setModel(model).setVector(vector);
	}

	private ClusterResponse speakerCluster(LoomHttpClient client, UUID assetUuid, Float[] centroid, UUID suggested) throws Exception {
		return speakerCluster(client, assetUuid, centroid, suggested, 0);
	}

	/**
	 * A speaker cluster the way the voiceprint node writes one: three speech windows - two overlapping, one apart - and a centroid in {@link #MODEL}.
	 */
	private ClusterResponse speakerCluster(LoomHttpClient client, UUID assetUuid, Float[] centroid, UUID suggested, int index) throws Exception {
		long[][] windows = { { 0, 3000 }, { 1500, 4500 }, { 6000, 9000 } };
		EmbeddingBulkCreateRequest embeddings = new EmbeddingBulkCreateRequest();
		for (int i = 0; i < windows.length; i++) {
			embeddings.getEmbeddings().add(new EmbeddingCreateRequest()
				.setType("voiceprint")
				.setNodeKind("voiceprint")
				.setModel(MODEL)
				.setVector(centroid)
				.setDimensions(centroid.length)
				.setFrameNumber(0)
				.setSubjectIndex(index * 10 + i)
				.setTimeFrom(windows[i][0])
				.setTimeTo(windows[i][1]));
		}
		List<EmbeddingResponse> stored = client.bulkCreateAssetEmbeddings(assetUuid, embeddings).sync().body().getEmbeddings();

		ClusterCreateItem item = new ClusterCreateItem()
			.setType("speaker")
			.setNodeKind("voiceprint")
			.setClusterIndex(index)
			.setCentroid(centroid)
			.setModel(MODEL)
			.setDimensions(centroid.length)
			.setSuggestedPersonUuid(suggested == null ? null : suggested.toString())
			.setSuggestedScore(suggested == null ? null : 0.77f);
		for (EmbeddingResponse embedding : stored) {
			item.add(new ClusterMemberCreateItem().setEmbeddingUuid(embedding.getUuid().toString()).setConfidence(0.9f).setOrigin("AUTO"));
		}
		ClusterBulkCreateRequest request = new ClusterBulkCreateRequest();
		request.add(item);
		request.setPruneStale(false);
		ClusterBulkResponse response = client.bulkCreateAssetClusters(assetUuid, request).sync().body();
		return response.getClusters().get(0);
	}

	/**
	 * A mono 16 kHz WAV of a tone with a little noise. The noise makes every file distinct: assets and attachment bytes are content-addressed, and two
	 * identical uploads would silently collapse into one.
	 */
	static File speechFile(int seconds, int frequency) throws IOException {
		int rate = 16_000;
		int samples = seconds * rate;
		ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < samples; i++) {
			double tone = Math.sin(2 * Math.PI * frequency * i / rate) * 0.3 + (Math.random() - 0.5) * 0.02;
			pcm.putShort((short) (tone * Short.MAX_VALUE));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes())
			.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2)
			.putShort((short) 16)
			.put("data".getBytes()).putInt(samples * 2);
		out.write(header.array());
		out.write(pcm.array());
		File file = File.createTempFile("voice-", ".wav");
		file.deleteOnExit();
		Files.write(file.toPath(), out.toByteArray());
		return file;
	}

	private static boolean ffmpegPresent() {
		try {
			Process probe = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			return probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}
}
