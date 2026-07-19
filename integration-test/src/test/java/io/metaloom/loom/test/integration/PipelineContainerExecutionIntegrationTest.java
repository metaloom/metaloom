package io.metaloom.loom.test.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.test.TestEnvHelper;
import io.metaloom.loom.test.container.CortexContainer;
import io.metaloom.loom.test.container.MetaLoomTestContext;
import io.metaloom.loom.test.data.TestDataCollection;
import io.metaloom.loom.test.data.TestMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The distributed pipeline, run entirely in containers.
 *
 * <pre>
 *   postgres      the database
 *   loom          owns the graph, decides everything
 *   cortex-source accepts only 'filesystem-source' -> walks the folder
 *   cortex-hash   accepts only 'sha512'            -> hashes what the scanner found
 * </pre>
 *
 * <p>This is the container counterpart to
 * {@link PipelineDistributedExecutionIntegrationTest}, which runs the same topology
 * inside one JVM. The JVM test is faster and easier to debug; this one covers what
 * that test structurally cannot, because it builds its components by calling
 * constructors and Dagger builders directly:</p>
 *
 * <ul>
 * <li>the Containerfiles, and the packaged jars inside them</li>
 * <li>configuration by environment variable - the JVM test sets
 * {@code CortexOptions} and {@code LoomOptions} by hand, so a broken
 * {@code @EnvironmentVariable} binding or a missing CLI option is invisible to it</li>
 * <li>real network hops between separate processes, rather than in-process calls</li>
 * </ul>
 *
 * <p>Both are worth having. Two defects found while writing this one were invisible
 * to the JVM test by construction: the cortex image shipped OpenCV 4.10 against
 * natives linked to 5.1 and so could not start at all, and {@code nodeKinds} /
 * {@code nodeId} had no CLI or environment binding, making a specialised worker
 * impossible to configure in a container.</p>
 */
public class PipelineContainerExecutionIntegrationTest {

	private static final Duration RUN_TIMEOUT = Duration.ofMinutes(3);

	private static final String SOURCE_WORKER_ID = "it-cortex-source";
	private static final String HASH_WORKER_ID = "it-cortex-hash";

	private MetaLoomTestContext ctx;

	@AfterEach
	public void tearDown() {
		if (ctx != null) {
			ctx.close();
		}
	}

	@Test
	public void testPipelineRunsAcrossTwoContainerisedWorkers() throws Exception {
		TestDataCollection data = TestEnvHelper.prepareTestdata("pipeline-container");
		TestMedia media = data.video1();
		String expectedSha512 = media.sha512().toString();

		// Only the one file the test asserts on is staged. The whole corpus is ~166 MiB
		// and it is copied into every worker, so staging all of it would move a third of
		// a gigabyte to prove one hash.
		Path staged = stageSingleMedia(data, media);

		// A container path: the workers see the media under /media, and the paths the
		// scanner reports are later opened by the hashing worker.
		String glob = CortexContainer.MEDIA_PATH + "/**/" + media.path().getFileName();

		ctx = new MetaLoomTestContext()
			.withMedia(staged)
			.withWorker(SOURCE_WORKER_ID, Set.of("filesystem-source"))
			.withWorker(HASH_WORKER_ID, Set.of("sha512"));
		ctx.start();

		UUID pipelineUuid;
		try (LoomHttpClient client = ctx.client()) {
			PipelineResponse pipeline = client.createPipeline(new PipelineCreateRequest()
				.setName("container-smoke")
				.setDefinition(definition())
				.setEnabled(true)).sync().body();
			pipelineUuid = pipeline.getUuid();
		}

		// ---------- First run: everything is computed ----------
		triggerRun(pipelineUuid, glob);
		UUID firstRun = awaitFinishedRun(pipelineUuid, 1);

		assertThat(itemPaths(firstRun))
			.as("The source worker should have discovered the media and reported it back to Loom")
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
		triggerRun(pipelineUuid, glob);
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

	/**
	 * Copy one media file into a directory of its own, keeping its path relative to the
	 * corpus root.
	 *
	 * <p>The relative path is preserved because the glob matches on it, and because a
	 * file sitting directly in the scan root would not exercise the recursive walk.</p>
	 *
	 * @return the staged root, to be mounted as the workers' media directory
	 */
	private Path stageSingleMedia(TestDataCollection data, TestMedia media) throws Exception {
		// media.path() is already rooted at the corpus, not relative to it.
		Path source = media.path();
		Path root = data.root().resolveSibling("media-" + media.path().getFileName());
		Path target = root.resolve(data.root().relativize(source));
		Files.createDirectories(target.getParent());
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		return root;
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

	/**
	 * Trigger a run.
	 *
	 * <p>Over plain HTTP because {@code LoomHttpClient} has no method for this endpoint
	 * yet - everything else here goes through the client.</p>
	 */
	private void triggerRun(UUID pipelineUuid, String glob) throws Exception {
		JsonObject body = new JsonObject().put("pathGlobs", new JsonArray().add(glob));
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(ctx.restUrl() + "/pipelines/" + pipelineUuid + "/run"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + ctx.adminToken())
			.POST(HttpRequest.BodyPublishers.ofString(body.encode()))
			.build();
		HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode())
			.as("Run request rejected: %s", response.body())
			.isBetween(200, 299);
	}

	/**
	 * Wait for the given run ordinal to reach a terminal state.
	 *
	 * @param ordinal 1 for the first run of this pipeline, 2 for the second
	 */
	private UUID awaitFinishedRun(UUID pipelineUuid, int ordinal) throws Exception {
		long deadline = System.currentTimeMillis() + RUN_TIMEOUT.toMillis();
		String lastStatus = "none";
		while (System.currentTimeMillis() < deadline) {
			List<String[]> runs = ctx.query(
				"SELECT uuid, status FROM pipeline_run WHERE pipeline_uuid = ? ORDER BY started ASC",
				ps -> ps.setObject(1, pipelineUuid), 2);
			if (runs.size() >= ordinal) {
				String[] row = runs.get(ordinal - 1);
				lastStatus = row[1];
				if (!"RUNNING".equals(lastStatus) && !"PENDING".equals(lastStatus)) {
					return UUID.fromString(row[0]);
				}
			}
			Thread.sleep(500);
		}
		throw new AssertionError("Run " + ordinal + " did not finish within " + RUN_TIMEOUT
			+ " (last status: " + lastStatus + ")");
	}

	// ----------------------------------------------------------------------
	// Assertions against the database
	// ----------------------------------------------------------------------

	private List<String> itemPaths(UUID runUuid) throws Exception {
		return ctx.query("SELECT media_path FROM pipeline_run_item WHERE run_uuid = ?",
			ps -> ps.setObject(1, runUuid), 1).stream().map(row -> row[0]).toList();
	}

	private String leasedBy(UUID runUuid, String nodeId) throws Exception {
		List<String[]> rows = ctx.query("SELECT leased_by FROM pipeline_node_task WHERE run_uuid = ? AND node_id = ?",
			ps -> {
				ps.setObject(1, runUuid);
				ps.setString(2, nodeId);
			}, 1);
		assertThat(rows).as("Expected exactly one '%s' task in run %s", nodeId, runUuid).hasSize(1);
		return rows.get(0)[0];
	}

	private String taskOutput(UUID runUuid, String nodeId, String outputKey) throws Exception {
		List<String[]> rows = ctx.query("SELECT outputs FROM pipeline_node_task WHERE run_uuid = ? AND node_id = ?",
			ps -> {
				ps.setObject(1, runUuid);
				ps.setString(2, nodeId);
			}, 1);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)[0]).as("Node '%s' recorded no outputs", nodeId).isNotNull();
		return new JsonObject(rows.get(0)[0]).getString(outputKey);
	}

	private boolean assetExistsWithSha512(String sha512) throws Exception {
		return !ctx.query("SELECT uuid FROM asset WHERE sha512sum = ?",
			ps -> ps.setString(1, sha512), 1).isEmpty();
	}
}
