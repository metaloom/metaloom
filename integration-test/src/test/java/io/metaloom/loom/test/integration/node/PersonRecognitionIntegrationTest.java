package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.filter.FilterBy;
import io.metaloom.cortex.node.filter.FilterNode;
import io.metaloom.cortex.node.filter.FilterNodeOptions;
import io.metaloom.cortex.node.filter.FilterStrategy;
import io.metaloom.cortex.node.filter.PersonFilterStrategy;
import io.metaloom.cortex.node.voiceprint.VoiceprintClient;
import io.metaloom.cortex.node.voiceprint.VoiceprintNode;
import io.metaloom.cortex.node.voiceprint.VoiceprintNodeOptions;
import io.metaloom.cortex.node.voiceprint.VoiceprintResult;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.cluster.ClusterConfirmRequest;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleImportRequest;
import io.metaloom.loom.rest.model.person.PersonVoiceSampleResponse;
import io.metaloom.loom.test.data.TestValues;
import io.metaloom.utils.hash.HashUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Person recognition across nodes and Loom: a voice sample uploaded to a person makes a speaker in a new recording recognisable, the voiceprint node
 * suggests the person, and the person filter routes on that - on the suggestion only when asked to, on the reviewer's confirmation by default.
 *
 * <p>
 * The first test stubs the sidecar, so it runs everywhere and pins the contract between the three parts. The second uses the real sidecar on its
 * two-voice fixture: one voice is cut out of one copy of the recording into a person, and must be recognised - and only that voice - in another copy.
 * It is skipped, not failed, without a sidecar answering {@code /health}.
 * </p>
 */
public class PersonRecognitionIntegrationTest extends AbstractNodeIntegrationTest implements TestValues {

	private static final String MODEL = "redimnet2-b6-vox2-lm";

	private static final Path FIXTURE_WAV = locate("sidecars/voiceprint/tests/data/two_speakers.wav");

	private static final Path FIXTURE_JSON = locate("sidecars/voiceprint/tests/data/two_speakers.json");

	private static Path locate(String relative) {
		for (Path base : List.of(Path.of(""), Path.of(".."))) {
			Path p = base.resolve(relative).toAbsolutePath().normalize();
			if (Files.exists(p)) {
				return p;
			}
		}
		return Path.of(relative);
	}

	private static JsonArray vec(double... v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}

	/** Two speakers on orthogonal axes; the pooled vector - what a single-speaker sample is enrolled with - lies on the first speaker's axis. */
	private static VoiceprintResult stubResult() {
		JsonObject json = new JsonObject()
			.put("model", MODEL).put("backend", "redimnet2").put("dim", 3).put("threshold", 0.4).put("duration_ms", 12_000).put("speech_ms", 9_000)
			.put("pooled", new JsonObject().put("vector", vec(1, 0, 0)))
			.put("speakers", new JsonArray()
				.add(new JsonObject().put("index", 0).put("total_ms", 6_000).put("cohesion", 0.93).put("windows", new JsonArray().add(0).add(1))
					.put("segments", new JsonArray().add(new JsonObject().put("start_ms", 1_000).put("end_ms", 5_500))).put("vector", vec(1, 0, 0)))
				.add(new JsonObject().put("index", 1).put("total_ms", 3_000).put("cohesion", 0.88).put("windows", new JsonArray().add(2))
					.put("segments", new JsonArray().add(new JsonObject().put("start_ms", 7_000).put("end_ms", 10_000))).put("vector", vec(0, 1, 0))))
			.put("windows", new JsonArray()
				.add(new JsonObject().put("index", 0).put("start_ms", 1_000).put("end_ms", 4_000).put("speaker", 0).put("vector", vec(0.99, 0.14, 0)))
				.add(new JsonObject().put("index", 1).put("start_ms", 2_500).put("end_ms", 5_500).put("speaker", 0).put("vector", vec(0.95, 0.31, 0)))
				.add(new JsonObject().put("index", 2).put("start_ms", 7_000).put("end_ms", 10_000).put("speaker", 1).put("vector", vec(0.1, 0.99, 0))));
		return VoiceprintResult.fromJson(json);
	}

	private static VoiceprintClient stubClient() {
		return new VoiceprintClient("localhost", 0) {
			@Override
			public VoiceprintResult analyze(Path audio, Params params, long timeoutMs) {
				return stubResult();
			}
		};
	}

	private FilterNode personFilter(LoomHttpClient client, String evidence) throws IOException {
		Provider<FilterStrategy> strategy = PersonFilterStrategy::new;
		FilterNode filter = new FilterNode(client, new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-person-filter")),
			new FilterNodeOptions(), Map.of(FilterBy.PERSON, strategy));
		filter.configure(new JsonObject()
			.put("id", "who")
			.put("filterBy", "PERSON")
			.put("personEvidence", evidence)
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "anna").put("match", "Anna Recognised"))
				.add(new JsonObject().put("id", "nobody").put("match", "nobody"))));
		return filter;
	}

	@Test
	public void testAnUploadedSampleMakesASpeakerRecognisableAndTheFilterRoutesOnIt() throws Exception {
		Assumptions.assumeTrue(Files.exists(FIXTURE_WAV), "fixture missing: " + FIXTURE_WAV);
		withLoom(client -> {
			PersonResponse anna = client.createPerson(new PersonCreateRequest().setAlias("Anna Recognised")).sync().body();
			PersonVoiceSampleResponse sample = client.uploadPersonVoiceSample(anna.getUuid(), FIXTURE_WAV.toFile(), "audio/wav", null).sync().body();
			assertThat(sample.getPrints()).as("nothing has embedded the upload yet").isEmpty();

			UniqueAsset asset = createUniqueAsset(client, "audio/wav", Files.readAllBytes(FIXTURE_WAV), ".wav");
			VoiceprintNode node = new VoiceprintNode(client, new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-recognition")),
				new VoiceprintNodeOptions(), stubClient());
			NodeResult result = node.process(NodeContext.create(asset.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			// The node embedded the sample with its own model on the way.
			PersonVoiceSampleResponse enrolled = client.listPersonVoiceSamples(anna.getUuid()).sync().body().getData().get(0);
			assertThat(enrolled.getPrints()).hasSize(1);
			assertThat(enrolled.getPrints().get(0).getModel()).isEqualTo(MODEL);
			assertThat(enrolled.getPrints().get(0).isUsable()).isTrue();

			List<ClusterResponse> speakers = speakerClusters(client, asset.asset().getUuid());
			assertThat(speakers).hasSize(2);
			assertThat(speakers.get(0).getSuggestedPersonUuid()).as("the first voice is Anna's").isEqualTo(anna.getUuid().toString());
			assertThat(speakers.get(0).getSuggestedPersonName()).isEqualTo("Anna Recognised");
			assertThat(speakers.get(0).getReviewStatus()).as("suggested, not confirmed").isEqualTo("PENDING");
			assertThat(speakers.get(1).getSuggestedPersonUuid()).as("the second voice is nobody's").isNull();

			JsonArray persons = new JsonArray((String) result.get(VoiceprintNode.OUT_PERSONS));
			assertThat(persons).hasSize(1);
			assertThat(persons.getJsonObject(0).getString("status")).isEqualTo("SUGGESTED");

			// A filter that only trusts reviewers sees nobody yet; one that accepts suggestions sees Anna.
			assertThat(personFilter(client, "CONFIRMED").process(NodeContext.create(asset.media())).get(FilterNode.OUT_BUCKET)).isEqualTo("nobody");
			assertThat(personFilter(client, "SUGGESTED").process(NodeContext.create(asset.media())).get(FilterNode.OUT_BUCKET)).isEqualTo("anna");

			// A reviewer confirms; now even the strict filter routes the recording to Anna - without re-running anything, because the filter
			// never caches a person verdict.
			client.confirmCluster(speakers.get(0).getUuid(), new ClusterConfirmRequest().setPersonUuid(anna.getUuid().toString())).sync().body();
			FilterNode strict = personFilter(client, "CONFIRMED");
			assertThat(strict.process(NodeContext.create(asset.media())).get(FilterNode.OUT_BUCKET)).isEqualTo("anna");
		});
	}

	/**
	 * The real thing: cut one voice out of one copy of the two-voice fixture into a person, then recognise it in a second copy. Only the speaker who
	 * is that voice may be suggested.
	 */
	@Test
	public void testRealSidecarRecognisesAVoiceCutFromAnotherRecording() throws Exception {
		VoiceprintNodeOptions nodeOptions = new VoiceprintNodeOptions();
		String why = sidecarUnavailable(nodeOptions);
		Assumptions.assumeTrue(why == null, why);
		Assumptions.assumeTrue(Files.exists(FIXTURE_WAV), "fixture missing: " + FIXTURE_WAV);
		JsonObject truth = new JsonObject(Files.readString(FIXTURE_JSON));

		withLoom(client -> {
			VoiceprintClient sidecar = new VoiceprintClient(nodeOptions.getVoiceprintHost(), nodeOptions.getVoiceprintPort());

			// First copy: uploaded with its bytes, so Loom can cut a sample out of it.
			File firstFile = salted(FIXTURE_WAV, "first");
			var first = client.uploadAsset(firstFile, LIBRARY_UUID, "audio/wav").sync().body();
			LoomMediaImpl firstMedia = new LoomMediaImpl(firstFile.toPath());
			firstMedia.setSHA512(HashUtils.computeSHA512(firstFile));
			NodeResult firstResult = new VoiceprintNode(client, new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-rec-a")),
				nodeOptions, sidecar).process(NodeContext.create(firstMedia));
			assertThat(firstResult.getState()).isEqualTo(ResultState.SUCCESS);
			ClusterResponse source = speakerClusters(client, first.getUuid()).get(0);
			String voice = voiceOfSpeaker(firstResult, truth, source.getClusterIndex());

			PersonResponse person = client.createPerson(new PersonCreateRequest().setAlias("Fixture Voice")).sync().body();
			PersonVoiceSampleResponse sample = client.importPersonVoiceSample(person.getUuid(), new PersonVoiceSampleImportRequest()
				.setClusterUuid(source.getUuid().toString())).sync().body();
			assertThat(sample.getDurationMs()).as("the longest turn, cut by Loom").isGreaterThan(1_000L);

			// Second copy: a new recording as far as Loom knows.
			UniqueAsset second = createUniqueAsset(client, "audio/wav", Files.readAllBytes(salted(FIXTURE_WAV, "second").toPath()), ".wav");
			NodeResult secondResult = new VoiceprintNode(client, new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-rec-b")),
				nodeOptions, sidecar).process(NodeContext.create(second.media()));
			assertThat(secondResult.getState()).isEqualTo(ResultState.SUCCESS);

			List<ClusterResponse> speakers = speakerClusters(client, second.asset().getUuid());
			List<ClusterResponse> suggested = speakers.stream().filter(c -> person.getUuid().toString().equals(c.getSuggestedPersonUuid())).toList();
			assertThat(suggested).as("exactly the one voice that was cut is recognised").hasSize(1);
			assertThat(voiceOfSpeaker(secondResult, truth, suggested.get(0).getClusterIndex())).isEqualTo(voice);
			assertThat(suggested.get(0).getSuggestedScore()).isGreaterThan(0.43f);
		});
	}

	private static List<ClusterResponse> speakerClusters(LoomHttpClient client, UUID assetUuid) throws Exception {
		return client.listAssetClusters(assetUuid).sync().body().getData().stream()
			.filter(c -> "speaker".equals(c.getType()))
			.sorted(Comparator.comparing(ClusterResponse::getClusterIndex))
			.toList();
	}

	/** The fixture voice a speaker of a timeline was first heard with. */
	private static String voiceOfSpeaker(NodeResult result, JsonObject truth, int speaker) {
		JsonArray segments = new JsonObject((String) result.get(VoiceprintNode.OUT_TIMELINE)).getJsonArray("segments");
		JsonArray turns = truth.getJsonArray("turns");
		int rate = truth.getInteger("rate");
		for (int i = 0; i < segments.size(); i++) {
			JsonObject seg = segments.getJsonObject(i);
			if (seg.getInteger("speaker") == speaker) {
				return voiceAt(turns, rate, seg.getLong("start"), seg.getLong("end"));
			}
		}
		return null;
	}

	private static String voiceAt(JsonArray turns, int rate, long startMs, long endMs) {
		String best = null;
		double overlap = 0;
		for (int i = 0; i < turns.size(); i++) {
			JsonObject t = turns.getJsonObject(i);
			double s = t.getLong("start") * 1000.0 / rate;
			double e = t.getLong("end") * 1000.0 / rate;
			double o = Math.max(0, Math.min(endMs, e) - Math.max(startMs, s));
			if (o > overlap) {
				overlap = o;
				best = t.getString("speaker");
			}
		}
		return best;
	}

	/** The fixture with a few trailing bytes, so two copies are two assets: assets are content-addressed. */
	private static File salted(Path wav, String salt) throws IOException {
		File file = File.createTempFile("recognition-" + salt + "-", ".wav");
		file.deleteOnExit();
		byte[] original = Files.readAllBytes(wav);
		byte[] tail = salt.getBytes();
		byte[] combined = new byte[original.length + tail.length];
		System.arraycopy(original, 0, combined, 0, original.length);
		System.arraycopy(tail, 0, combined, original.length, tail.length);
		Files.write(file.toPath(), combined);
		return file;
	}

	private static String sidecarUnavailable(VoiceprintNodeOptions options) {
		String url = "http://" + options.getVoiceprintHost() + ":" + options.getVoiceprintPort() + "/health";
		try {
			HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() != 200 || !new JsonObject(r.body()).containsKey("backends")) {
				return "no voiceprint sidecar at " + url + " (HTTP " + r.statusCode() + ")";
			}
			return null;
		} catch (IOException | InterruptedException | RuntimeException e) {
			return "no voiceprint sidecar at " + url + " - start sidecars/voiceprint/run.sh or container.sh run (" + e.getMessage() + ")";
		}
	}
}
