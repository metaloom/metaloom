package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.voiceprint.VoiceprintClient;
import io.metaloom.cortex.node.voiceprint.VoiceprintNode;
import io.metaloom.cortex.node.voiceprint.VoiceprintNodeOptions;
import io.metaloom.cortex.node.voiceprint.VoiceprintResult;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.cluster.ClusterMemberModel;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code VoiceprintNode} against a real Loom.
 *
 * <p>
 * The first test replaces the sidecar with a stub returning a fixed two-speaker answer and checks that every write is readable back through REST:
 * window embeddings with their time range, one {@code speaker} cluster per voice holding those windows, the timeline component and the ledger.
 * </p>
 *
 * <p>
 * The second runs the whole chain with the <strong>real</strong> sidecar ({@code sidecars/voiceprint}, :9150) on its two-voice fixture, and is
 * skipped - not failed - when no voiceprint sidecar answers {@code /health}. A green build does not prove it ran; the skip reason says so.
 * </p>
 */
public class VoiceprintNodeIntegrationTest extends AbstractNodeIntegrationTest {

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

	private static VoiceprintResult stubResult() {
		JsonObject json = new JsonObject()
			.put("model", MODEL).put("backend", "redimnet2").put("dim", 3).put("threshold", 0.4).put("duration_ms", 12_000).put("speech_ms", 9_000)
			.put("pooled", new JsonObject().put("vector", vec(0.6, 0.8, 0.0)))
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

	@Test
	public void testVoiceprintPersistsEmbeddingsClustersComponentAndLedger() throws Exception {
		Assumptions.assumeTrue(Files.exists(FIXTURE_WAV), "fixture missing: " + FIXTURE_WAV);
		withLoom(client -> {
			UniqueAsset asset = createUniqueAsset(client, "audio/wav", Files.readAllBytes(FIXTURE_WAV), ".wav");
			CortexOptions options = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-voiceprint"));
			VoiceprintNode node = new VoiceprintNode(client, options, new VoiceprintNodeOptions(), stubClient());

			NodeResult result = node.process(NodeContext.create(asset.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(VoiceprintNode.OUT_SPEAKER_COUNT)).isEqualTo(2L);

			assertPersisted(client, asset.asset().getUuid(), 2, 3);
		});
	}

	/**
	 * Follows every write back out through REST, as NEW_NODE.md section 1.4 asks: clusters by asset, their members, each member's embedding with its
	 * time window, the component and the ledger.
	 */
	private void assertPersisted(LoomHttpClient client, UUID assetUuid, int speakers, int windows) throws Exception {
		List<ClusterResponse> clusters = client.listAssetClusters(assetUuid).sync().body().getData().stream()
			.filter(c -> "speaker".equals(c.getType()))
			.sorted(Comparator.comparing(ClusterResponse::getClusterIndex))
			.toList();
		assertThat(clusters).as("one speaker cluster per voice").hasSize(speakers);
		assertThat(clusters.get(0).getNodeKind()).isEqualTo("voiceprint");

		int members = 0;
		for (ClusterResponse cluster : clusters) {
			List<ClusterMemberModel> list = client.listClusterMembers(cluster.getUuid()).sync().body().getMembers();
			assertThat(list).as("cluster %d has members", cluster.getClusterIndex()).isNotEmpty();
			for (ClusterMemberModel member : list) {
				EmbeddingResponse embedding = client.loadEmbedding(UUID.fromString(member.getEmbeddingUuid())).sync().body();
				assertThat(embedding.getType()).isEqualTo("voiceprint");
				assertThat(embedding.getTimeFrom()).as("a voiceprint says when the voice was heard").isNotNull();
				assertThat(embedding.getTimeTo()).isGreaterThan(embedding.getTimeFrom());
				assertThat(embedding.getNormalized()).isTrue();
				assertThat(embedding.getDetectionUuid()).isNull();
				members++;
			}
		}
		assertThat(members).as("every window is a member of exactly one speaker").isEqualTo(windows);

		JsonCompResponse comp = client.listAssetJsonComps(assetUuid).sync().body().getData().stream()
			.filter(c -> "voiceprint".equals(c.getSchemaType()))
			.findFirst().orElse(null);
		assertThat(comp).as("voiceprint timeline component must be readable via REST").isNotNull();
		assertThat(comp.getData().getInteger("speakerCount")).isEqualTo(speakers);
		assertThat(comp.getData().encode()).doesNotContain("vector");

		NodeResultResponse ledger = client.listAssetNodeResults(assetUuid).sync().body().getData().stream()
			.filter(r -> "voiceprint".equals(r.getNodeKind()))
			.findFirst().orElse(null);
		assertThat(ledger).as("voiceprint ledger row must be readable via REST").isNotNull();
		assertThat(ledger.getState()).isEqualTo("SUCCESS");
	}

	@Test
	public void testRealSidecarSeparatesTheTwoVoices() throws Exception {
		VoiceprintNodeOptions nodeOptions = new VoiceprintNodeOptions();
		String why = sidecarUnavailable(nodeOptions);
		Assumptions.assumeTrue(why == null, why);
		Assumptions.assumeTrue(Files.exists(FIXTURE_WAV), "fixture missing: " + FIXTURE_WAV);
		JsonObject truth = new JsonObject(Files.readString(FIXTURE_JSON));

		withLoom(client -> {
			UniqueAsset asset = createUniqueAsset(client, "audio/wav", Files.readAllBytes(FIXTURE_WAV), ".wav");
			CortexOptions options = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-voiceprint-live"));
			VoiceprintNode node = new VoiceprintNode(client, options, nodeOptions,
				new VoiceprintClient(nodeOptions.getVoiceprintHost(), nodeOptions.getVoiceprintPort()));

			LoomMedia media = asset.media();
			NodeResult result = node.process(NodeContext.create(media));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(VoiceprintNode.OUT_SPEAKER_COUNT)).as("two TTS voices take turns in the fixture").isEqualTo(2L);

			// Every speaker turn of the timeline must sit on one fixture turn, and both voices must alternate as the fixture does.
			JsonArray segments = new JsonObject((String) result.get(VoiceprintNode.OUT_TIMELINE)).getJsonArray("segments");
			JsonArray turns = truth.getJsonArray("turns");
			int rate = truth.getInteger("rate");
			String[] voiceOfSpeaker = new String[2];
			for (int i = 0; i < segments.size(); i++) {
				JsonObject seg = segments.getJsonObject(i);
				String voice = voiceAt(turns, rate, seg.getLong("start"), seg.getLong("end"));
				int speaker = seg.getInteger("speaker");
				if (voiceOfSpeaker[speaker] == null) {
					voiceOfSpeaker[speaker] = voice;
				}
				assertThat(voice).as("segment %s belongs to the voice its speaker was first heard with", seg).isEqualTo(voiceOfSpeaker[speaker]);
			}
			assertThat(voiceOfSpeaker[0]).isNotEqualTo(voiceOfSpeaker[1]);

			JsonObject summary = new JsonObject((String) result.get(VoiceprintNode.OUT_RESULT));
			assertPersisted(client, asset.asset().getUuid(), 2, summary.getJsonArray("windows").size());
		});
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

	/**
	 * A protocol probe, not a TCP one: something else may hold the port. The sidecar answers /health with a {@code backends} map.
	 *
	 * @return null when the sidecar is there, else the reason the live test is skipped
	 */
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
