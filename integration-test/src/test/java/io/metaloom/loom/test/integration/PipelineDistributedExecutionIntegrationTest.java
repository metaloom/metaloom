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
 * A real pipeline across a real Loom and two real Cortex workers, in one JVM.
 *
 * <p>Everything else written for the Variant C architecture is either a unit test or
 * uses a fake dispatcher — {@code PipelineRunEndToEndTest} says in its own javadoc
 * that "the transport is deliberately left out". Nothing has ever connected a real
 * worker to a real Loom over the processor WebSocket, so nothing has ever proven
 * that registration, whitelisting, dispatch, results and persistence actually fit
 * together.</p>
 *
 * <h2>The topology</h2>
 *
 * <pre>
 *   Loom          owns the graph, decides everything
 *   Cortex A      accepts only 'filesystem-source'  -> walks the folder
 *   Cortex B      accepts only 'sha512'             -> hashes what A found
 * </pre>
 *
 * <p>The split is the point. If both workers accepted everything, the test would
 * prove that <em>a</em> worker did the job, not that Loom routed the job to the
 * right one.</p>
 *
 * <p>Assertions go through the database rather than the API because what is under
 * test is who did what: {@code pipeline_node_task.leased_by} records the worker,
 * and there is no REST surface exposing it (that is a known open task).</p>
 */
public class PipelineDistributedExecutionIntegrationTest extends AbstractIntegrationTest {

	/** How long to wait for a run to finish before calling it hung. */
	private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);

	private static final String SOURCE_WORKER_ID = "it-cortex-source";
	private static final String HASH_WORKER_ID = "it-cortex-hash";

	private Loom server;
	private Cortex sourceWorker;
	private Cortex hashWorker;
	private final List<Path> tempDirs = new ArrayList<>();

	@AfterEach
	public void tearDown() {
		// shutdown(), never shutdownAndTerminate() - the latter calls Runtime.exit and
		// would take the whole test JVM with it.
		if (hashWorker != null) {
			hashWorker.shutdown();
		}
		if (sourceWorker != null) {
			sourceWorker.shutdown();
		}
		if (server != null) {
			server.shutdown();
		}
	}

	@Test
	public void testPipelineRunsAcrossTwoSpecialisedWorkers() throws Exception {
		server = loomServer();
		server.run(false);
		int restPort = server.actualRestPort();

		TestDataCollection data = TestEnvHelper.prepareTestdata("pipeline-distributed");
		TestMedia media = data.video1();
		// The corpus is ~166 MiB. Narrow the scan to one file with a known hash so the
		// test stays quick and can assert the *correct* value, not merely some value.
		String glob = data.root().toAbsolutePath() + "/**/" + media.path().getFileName();
		String expectedSha512 = media.sha512().toString();

		sourceWorker = startWorker(SOURCE_WORKER_ID, restPort, Set.of("filesystem-source"));
		hashWorker = startWorker(HASH_WORKER_ID, restPort, Set.of("sha512"));

		String token;
		UUID pipelineUuid;
		try (LoomHttpClient client = httpClient(server)) {
			AuthLoginResponse login = client.login("admin", "finger").sync().body();
			token = login.getToken();
			client.setToken(token);

			awaitProcessors(restPort, token, 2);

			PipelineResponse pipeline = client.createPipeline(new PipelineCreateRequest()
				.setName("distributed-smoke")
				.setDefinition(definition())
				.setEnabled(true)).sync().body();
			pipelineUuid = pipeline.getUuid();
		}

		// ---------- First run: everything is computed ----------
		triggerRun(restPort, token, pipelineUuid, glob);
		UUID firstRun = awaitFinishedRun(pipelineUuid, 1);

		List<String> itemPaths = itemPaths(firstRun);
		assertThat(itemPaths)
			.as("Cortex A should have discovered the media and reported it back to Loom")
			.isNotEmpty()
			.allSatisfy(p -> assertThat(p).endsWith(".mp4"));

		assertThat(leasedBy(firstRun, "hash"))
			.as("The hash must run on the worker that accepts 'sha512', not on the scanner")
			.isEqualTo(HASH_WORKER_ID);

		assertThat(taskOutput(firstRun, "hash", "sha512"))
			.as("The computed hash must reach Loom and be the correct one")
			.isEqualTo(expectedSha512);

		assertThat(assetExistsWithSha512(expectedSha512))
			.as("syncToLoom should have persisted the hash onto an asset")
			.isTrue();

		// ---------- Second run: the hash is already known ----------
		triggerRun(restPort, token, pipelineUuid, glob);
		UUID secondRun = awaitFinishedRun(pipelineUuid, 2);

		assertThat(itemPaths(secondRun))
			.as("The source runs again - discovery is never reused")
			.isNotEmpty();

		assertThat(leasedBy(secondRun, "hash"))
			.as("The hash result was already known, so no worker should have been asked to recompute it")
			.isNull();

		assertThat(taskOutput(secondRun, "hash", "sha512"))
			.as("The reused result must carry its outputs forward, not settle as an empty skip")
			.isEqualTo(expectedSha512);
	}

	// ----------------------------------------------------------------------
	// Harness
	// ----------------------------------------------------------------------

	/**
	 * Start a worker restricted to the given node kinds.
	 *
	 * <p>Built through the Dagger component rather than {@code Cortex.create()}, which
	 * throws {@code UnsupportedOperationException}. Each instance gets its own
	 * component, so {@code @Singleton} state is per-worker; the two things that must
	 * be distinct by hand are the monitoring port and the meta path.</p>
	 */
	private Cortex startWorker(String nodeId, int loomRestPort, Set<String> nodeKinds) throws Exception {
		Path metaPath = Files.createTempDirectory(Paths.get("target"), nodeId);
		tempDirs.add(metaPath);

		CortexOptions options = new CortexOptions();
		options.setMetaPath(metaPath);
		// A known id, so assertions can name the worker that was expected to do the work.
		options.setNodeId(nodeId);
		// 0 => ephemeral. Two workers on the 8093 default would fail the second bind.
		options.setMonitoringPort(0);
		options.setNodeWhitelist(nodeKinds);
		options.getLoom().setHostname("localhost").setPort(loomRestPort);

		Cortex cortex = DaggerCortexComponent.builder().options(options).build().cortex();
		cortex.run(false);
		return cortex;
	}

	/** The pipeline under test: scan, then hash, and persist the hash. */
	private JsonObject definition() {
		return new JsonObject()
			// Adopt a previous run's result for unchanged files. Off by default, so the
			// second-run behaviour is something this pipeline asks for.
			.put("reuseResults", true)
			.put("nodes", new JsonArray()
				.add(new JsonObject()
					.put("id", "scan")
					.put("type", "filesystem-source")
					.put("source", true))
				.add(new JsonObject()
					.put("id", "hash")
					.put("type", "sha512")
					// Without this the hash is recorded against the run and never
					// reaches the asset.
					.put("syncToLoom", true)))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "scan").put("target", "hash")));
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
		assertThat(response.statusCode())
			.as("Run request rejected: %s", response.body())
			.isBetween(200, 299);
	}

	/**
	 * Wait until both workers have registered.
	 *
	 * <p>Connection is asynchronous with a reconnect backoff, so this polls rather than
	 * sleeping. Deliberately not keyed on the heartbeat (10 s) or status (20 s) timers
	 * — waiting on those would make the test slow for no added confidence.</p>
	 */
	private void awaitProcessors(int restPort, String token, int expected) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + restPort + "/api/v1/processors"))
			.header("Authorization", "Bearer " + token)
			.GET().build();

		long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
		String lastBody = "";
		while (System.currentTimeMillis() < deadline) {
			HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());
			lastBody = response.body();
			// The listing keys workers under "uuid", not "nodeId".
			if (countOccurrences(lastBody, "\"state\"") >= expected) {
				return;
			}
			Thread.sleep(250);
		}
		throw new AssertionError("Only " + countOccurrences(lastBody, "\"state\"") + " of " + expected
			+ " workers registered within 60s. Last response: " + lastBody);
	}

	/**
	 * Wait for the given run ordinal to reach a terminal state.
	 *
	 * @param ordinal 1 for the first run of this pipeline, 2 for the second
	 * @return the run uuid
	 */
	private UUID awaitFinishedRun(UUID pipelineUuid, int ordinal) throws Exception {
		long deadline = System.currentTimeMillis() + RUN_TIMEOUT.toMillis();
		String lastStatus = "none";
		while (System.currentTimeMillis() < deadline) {
			List<String[]> runs = query(
				"SELECT uuid, status FROM pipeline_run WHERE pipeline_uuid = ? ORDER BY started ASC",
				ps -> ps.setObject(1, pipelineUuid), 2);
			if (runs.size() >= ordinal) {
				String[] row = runs.get(ordinal - 1);
				lastStatus = row[1];
				if (!"RUNNING".equals(lastStatus) && !"PENDING".equals(lastStatus)) {
					return UUID.fromString(row[0]);
				}
			}
			Thread.sleep(250);
		}
		throw new AssertionError("Run " + ordinal + " did not finish within " + RUN_TIMEOUT
			+ " (last status: " + lastStatus + ")");
	}

	// ----------------------------------------------------------------------
	// Assertions against the database
	// ----------------------------------------------------------------------

	private List<String> itemPaths(UUID runUuid) throws Exception {
		return query("SELECT media_path FROM pipeline_run_item WHERE run_uuid = ?",
			ps -> ps.setObject(1, runUuid), 1).stream().map(row -> row[0]).toList();
	}

	private String leasedBy(UUID runUuid, String nodeId) throws Exception {
		List<String[]> rows = query("SELECT leased_by FROM pipeline_node_task WHERE run_uuid = ? AND node_id = ?",
			ps -> {
				ps.setObject(1, runUuid);
				ps.setString(2, nodeId);
			}, 1);
		assertThat(rows).as("Expected exactly one '%s' task in run %s", nodeId, runUuid).hasSize(1);
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
		return new JsonObject(rows.get(0)[0]).getString(outputKey);
	}

	private boolean assetExistsWithSha512(String sha512) throws Exception {
		return !query("SELECT uuid FROM asset WHERE sha512sum = ?",
			ps -> ps.setString(1, sha512), 1).isEmpty();
	}

	@FunctionalInterface
	private interface Binder {
		void bind(PreparedStatement ps) throws Exception;
	}

	/**
	 * Small JDBC helper.
	 *
	 * <p>The run and task tables have no REST surface yet, and the server owns its own
	 * Dagger graph, so reading the database directly is the only way to assert which
	 * worker did what.</p>
	 */
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
