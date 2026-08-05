package io.metaloom.loom.test.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.DaggerCortexComponent;
import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.test.TestEnvHelper;
import io.metaloom.loom.test.data.TestDataCollection;
import io.metaloom.loom.test.data.TestMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Affinity: three nodes pinned to one worker, dispatched as a single unit.
 *
 * <p>{@link PipelineDistributedExecutionIntegrationTest} proves work is routed to the
 * right worker. This proves the opposite property - that work which belongs
 * <em>together</em> is not split up. Three analysis nodes share an affinity group,
 * so Loom sends them as one {@code SEGMENT_TASK}, the worker runs all three with
 * intermediate results staying in its memory, and only the finished set comes back.
 * Under per-node dispatch this same graph would cost three round trips.</p>
 *
 * <h2>The topology</h2>
 *
 * <pre>
 *   Cortex SCAN      filesystem-source + sha512              walks the folder, hashes
 *   Cortex ANALYSE   consistency + thumbnail + fingerprint   runs the whole segment
 *   Cortex PARTIAL   thumbnail only                          must NOT receive the segment
 * </pre>
 *
 * <p>The third worker is the point of the test. It can run one of the segment's
 * three kinds, so a naive "some worker handles each kind" placement would happily
 * split the group across it. A segment needs <em>one</em> worker covering
 * <em>every</em> kind, and this is what proves that rule is enforced rather than
 * merely intended.</p>
 */
public class PipelineAffinitySegmentIntegrationTest extends AbstractIntegrationTest {

	private static final Duration RUN_TIMEOUT = Duration.ofMinutes(3);

	private static final String SCAN_WORKER = "it-cortex-scan";
	private static final String ANALYSE_WORKER = "it-cortex-analyse";
	private static final String PARTIAL_WORKER = "it-cortex-partial";

	/** The nodes that must end up together. */
	private static final List<String> SEGMENT_NODES = List.of("check", "thumb", "fp");

	private Loom server;
	private final List<Cortex> workers = new ArrayList<>();

	@AfterEach
	public void tearDown() {
		workers.forEach(Cortex::shutdown);
		if (server != null) {
			server.shutdown();
		}
	}

	@Test
	public void testAnAffinityGroupRunsWhollyOnOneWorker() throws Exception {
		server = loomServer();
		server.run(false);
		int restPort = server.actualRestPort();

		TestDataCollection data = TestEnvHelper.prepareTestdata("pipeline-affinity");
		TestMedia media = data.video1();
		String glob = data.root().toAbsolutePath() + "/**/" + media.path().getFileName();
		String expectedSha512 = media.sha512().toString();

		// sha512 lives here rather than on ANALYSE so that the hash node, which is outside the
		// affinity group, cannot muddy the "who ran the segment" assertions below.
		startWorker(SCAN_WORKER, restPort, Set.of("filesystem-source", "sha512"));
		startWorker(ANALYSE_WORKER, restPort, Set.of("consistency", "thumbnail", "fingerprint"));
		// Can run one of the segment's kinds, but not all three. It must be passed over.
		startWorker(PARTIAL_WORKER, restPort, Set.of("thumbnail"));

		String token;
		UUID pipelineUuid;
		try (LoomHttpClient client = httpClient(server)) {
			AuthLoginResponse login = client.login("admin", "finger").sync().body();
			token = login.getToken();
			client.setToken(token);

			awaitProcessors(restPort, token, 3);

			PipelineResponse pipeline = client.createPipeline(new PipelineCreateRequest()
				.setName("affinity-segment")
				.setDefinition(definition())
				.setEnabled(true)).sync().body();
			pipelineUuid = pipeline.getUuid();
		}

		triggerRun(restPort, token, pipelineUuid, glob);
		UUID runUuid = awaitFinishedRun(pipelineUuid);

		// ---------- Every node of the group ran, and all on one machine ----------
		List<String[]> tasks = tasksFor(runUuid);
		assertThat(tasks).as("Every node of the segment must be accounted for")
			.extracting(row -> row[0])
			.containsAll(SEGMENT_NODES);

		List<String> segmentWorkers = tasks.stream()
			.filter(row -> SEGMENT_NODES.contains(row[0]))
			.map(row -> row[1])
			.distinct()
			.toList();

		assertThat(segmentWorkers)
			.as("The whole affinity group must run on exactly one worker - that is the entire point")
			.hasSize(1);
		assertThat(segmentWorkers.get(0))
			.as("It must be the worker that can run all three kinds, not the one that can run only thumbnails")
			.isEqualTo(ANALYSE_WORKER);

		// ---------- Nothing was handed to the partially-capable worker ----------
		assertThat(tasks).as("A worker covering only part of the group must be passed over entirely")
			.noneSatisfy(row -> assertThat(row[1]).isEqualTo(PARTIAL_WORKER));

		// ---------- One dispatch, not three ----------
		// Each member owns its row - the uuid is the primary key, so it cannot be the shared id -
		// and records the request that carried it in meta.dispatchUuid.
		List<String> dispatchIds = tasks.stream()
			.filter(row -> SEGMENT_NODES.contains(row[0]))
			.map(row -> row[4])
			.distinct()
			.toList();
		assertThat(dispatchIds)
			.as("Three nodes sent as one segment share a single dispatch; three ids would mean "
				+ "three round trips and no saving at all")
			.hasSize(1);
		assertThat(dispatchIds.get(0)).as("...and it must be a real dispatch, not an absent one").isNotNull();

		assertThat(tasks.stream().filter(row -> SEGMENT_NODES.contains(row[0])).map(row -> row[3]).distinct())
			.as("Every member still needs a row of its own; a shared row id would collide on the "
				+ "primary key and silently drop all but the first node's outcome")
			.hasSize(SEGMENT_NODES.size());

		// ---------- The results still came back individually ----------
		assertThat(taskOutput(runUuid, "hash", "hash"))
			.as("Per-node outcomes must survive the segment; one verdict for the group would lose them")
			.isEqualTo(expectedSha512);
		for (String nodeId : SEGMENT_NODES) {
			assertThat(taskState(runUuid, nodeId))
				.as("Node '%s' should have completed", nodeId)
				.isEqualTo("COMPLETED");
		}

		// ---------- And reached the asset ----------
		assertThat(assetExistsWithSha512(expectedSha512))
			.as("Only after the whole group finished should the data be persisted")
			.isTrue();
	}

	/**
	 * scan -> hash, and scan -> (check -> thumb, check -> fp) with the last three sharing one
	 * affinity group.
	 *
	 * <p>The three would group on their shared producer alone — they all read {@code scan.media} —
	 * so this deliberately wires them as well: {@code consistency}'s {@code is_complete} verdict
	 * feeds the optional {@code is_complete} input of both {@code thumbnail} and
	 * {@code fingerprint}, which is exactly what that port is for. Real edges are what make the
	 * per-node outputs below meaningful, since a member only sees a fellow member's output when it
	 * declares the dependency.</p>
	 *
	 * <p>⚠️ A chain of hashing nodes cannot express this. Under the typed port model an edge must
	 * join two compatible ports, and {@code sha512} emits {@code hash/sha512} while every analysis
	 * node consumes {@code media/*} - so {@code hash -> chunks -> thumb} is not a wiring the parser
	 * will accept, it is three siblings of one source. That is precisely why sharing a producer
	 * groups nodes: under an edges-only rule those three fused into three segments and the saving
	 * disappeared. Here {@code hash} stays out of the group by carrying no affinity label, so the
	 * "who ran the segment" assertions stay unambiguous.</p>
	 */
	private JsonObject definition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "scan").put("type", "filesystem-source").put("source", true))
				// Outside the group, and runnable only by the scan worker.
				.add(new JsonObject().put("id", "hash").put("type", "sha512").put("syncToLoom", true))
				.add(new JsonObject().put("id", "check").put("type", "consistency")
					.put("affinity", "analysis"))
				.add(new JsonObject().put("id", "thumb").put("type", "thumbnail")
					.put("affinity", "analysis"))
				.add(new JsonObject().put("id", "fp").put("type", "fingerprint")
					.put("affinity", "analysis")))
			.put("edges", new JsonArray()
				.add(edge("scan", "media", "hash", "media"))
				.add(edge("scan", "media", "check", "media"))
				.add(edge("scan", "media", "thumb", "media"))
				.add(edge("scan", "media", "fp", "media"))
				// The two edges that make the group one connected unit rather than three siblings.
				.add(edge("check", "is_complete", "thumb", "is_complete"))
				.add(edge("check", "is_complete", "fp", "is_complete")));
	}

	private static JsonObject edge(String from, String fromPort, String to, String toPort) {
		return new JsonObject()
			.put("source", from).put("sourcePort", fromPort)
			.put("target", to).put("targetPort", toPort);
	}

	// ----------------------------------------------------------------------
	// Harness
	// ----------------------------------------------------------------------

	private void startWorker(String nodeId, int loomRestPort, Set<String> nodeKinds) throws Exception {
		Path metaPath = Files.createTempDirectory(Paths.get("target"), nodeId);

		CortexOptions options = new CortexOptions();
		options.setMetaPath(metaPath);
		options.setNodeId(nodeId);
		// 0 => ephemeral; three workers on the 8093 default would collide.
		options.setMonitoringPort(0);
		options.setNodeWhitelist(nodeKinds);
		options.getLoom().setHostname("localhost").setPort(loomRestPort);

		Cortex cortex = DaggerCortexComponent.builder().options(options).build().cortex();
		cortex.run(false);
		workers.add(cortex);
	}

	private void triggerRun(int restPort, String token, UUID pipelineUuid, String glob) throws Exception {
		JsonObject body = new JsonObject().put("pathGlobs", new JsonArray().add(glob));
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + restPort + "/api/v1/pipelines/" + pipelineUuid + "/run"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + token)
			.POST(HttpRequest.BodyPublishers.ofString(body.encode()))
			.build();
		HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("Run rejected: %s", response.body()).isBetween(200, 299);
	}

	private void awaitProcessors(int restPort, String token, int expected) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + restPort + "/api/v1/processors"))
			.header("Authorization", "Bearer " + token)
			.GET().build();

		long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
		String lastBody = "";
		while (System.currentTimeMillis() < deadline) {
			lastBody = java.net.http.HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString()).body();
			if (countOccurrences(lastBody, "\"state\"") >= expected) {
				return;
			}
			Thread.sleep(250);
		}
		throw new AssertionError("Only " + countOccurrences(lastBody, "\"state\"") + " of " + expected
			+ " workers registered. Last response: " + lastBody);
	}

	private UUID awaitFinishedRun(UUID pipelineUuid) throws Exception {
		long deadline = System.currentTimeMillis() + RUN_TIMEOUT.toMillis();
		String lastStatus = "none";
		while (System.currentTimeMillis() < deadline) {
			List<String[]> runs = query("SELECT uuid, status FROM pipeline_run WHERE pipeline_uuid = ?",
				ps -> ps.setObject(1, pipelineUuid), 2);
			if (!runs.isEmpty()) {
				lastStatus = runs.get(0)[1];
				if (!"RUNNING".equals(lastStatus) && !"PENDING".equals(lastStatus)) {
					return UUID.fromString(runs.get(0)[0]);
				}
			}
			Thread.sleep(250);
		}
		throw new AssertionError("Run did not finish within " + RUN_TIMEOUT + " (last status: " + lastStatus + ")");
	}

	// ----------------------------------------------------------------------
	// Assertions against the database
	// ----------------------------------------------------------------------

	/** @return rows of {nodeId, leasedBy, state, taskUuid, dispatchUuid} */
	private List<String[]> tasksFor(UUID runUuid) throws Exception {
		return query("SELECT node_id, leased_by, state, uuid, meta->>'dispatchUuid'"
			+ " FROM pipeline_node_task WHERE run_uuid = ?", ps -> ps.setObject(1, runUuid), 5);
	}

	private String taskState(UUID runUuid, String nodeId) throws Exception {
		List<String[]> rows = query("SELECT state FROM pipeline_node_task WHERE run_uuid = ? AND node_id = ?",
			ps -> {
				ps.setObject(1, runUuid);
				ps.setString(2, nodeId);
			}, 1);
		assertThat(rows).as("Expected one '%s' task", nodeId).hasSize(1);
		return rows.get(0)[0];
	}

	private String taskOutput(UUID runUuid, String nodeId, String outputKey) throws Exception {
		List<String[]> rows = query("SELECT outputs FROM pipeline_node_task WHERE run_uuid = ? AND node_id = ?",
			ps -> {
				ps.setObject(1, runUuid);
				ps.setString(2, nodeId);
			}, 1);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)[0]).as("Node '%s' recorded no outputs", nodeId).isNotNull();
		// The column holds one port payload envelope per output port, not a flat key/value bag:
		// {"<port>": {"contentType": ..., "cardinality": ..., "elements": [{"value": ..., "origin": ...}]}}.
		return new JsonObject(rows.get(0)[0]).getJsonObject(outputKey)
			.getJsonArray("elements").getJsonObject(0).getString("value");
	}

	private boolean assetExistsWithSha512(String sha512) throws Exception {
		return !query("SELECT uuid FROM asset WHERE sha512sum = ?", ps -> ps.setString(1, sha512), 1).isEmpty();
	}

	@FunctionalInterface
	private interface Binder {
		void bind(PreparedStatement ps) throws Exception;
	}

	private List<String[]> query(String sql, Binder binder, int columns) throws Exception {
		DatabaseOptions db = loomOptions().getDatabase();
		String url = "jdbc:postgresql://" + db.getHost() + ":" + db.getPort() + "/" + db.getDatabaseName();
		List<String[]> rows = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(url, db.getUsername(), db.getPassword());
			PreparedStatement ps = connection.prepareStatement(sql)) {
			binder.bind(ps);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String[] row = new String[columns];
					for (int i = 0; i < columns; i++) {
						row[i] = rs.getString(i + 1);
					}
					rows.add(row);
				}
			}
		}
		return rows;
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int index = 0;
		while ((index = haystack.indexOf(needle, index)) != -1) {
			count++;
			index += needle.length();
		}
		return count;
	}

}
