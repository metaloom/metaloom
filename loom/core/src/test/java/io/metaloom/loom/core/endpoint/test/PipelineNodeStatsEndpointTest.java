package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeStatsResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTimeStats;

/**
 * {@code GET /pipelines/:uuid/node-stats}: where a pipeline's execution time goes, per node, over its latest runs or one run.
 */
public class PipelineNodeStatsEndpointTest extends AbstractEndpointTest {

	/** A pipeline with two runs: the first computed everything, the second reused the transcription. */
	private record Fixture(Pipeline pipeline, PipelineRun first, PipelineRun second, PipelineRunItem secondItem) {
	}

	private Fixture fixture() {
		UUID admin = adminUuid();
		Pipeline pipeline = daos().pipelineDao().createPipeline(admin, "node-stats-" + UUID.randomUUID());
		daos().pipelineDao().store(pipeline);
		PipelineRun first = run(pipeline, Instant.now().minusSeconds(600));
		PipelineRun second = run(pipeline, Instant.now().minusSeconds(60));
		PipelineRunItem a = item(first, 0);
		task(a, "transcribe", "whisper", 30_000L, "COMPUTED");
		task(a, "hash", "sha512", 900L, "COMPUTED");
		PipelineRunItem b = item(first, 1);
		task(b, "transcribe", "whisper", 50_000L, "COMPUTED");
		task(b, "hash", "sha512", 1_100L, "COMPUTED");
		PipelineRunItem c = item(second, 0);
		task(c, "transcribe", "whisper", 12L, "REMOTE");
		task(c, "hash", "sha512", 1_000L, "COMPUTED");
		return new Fixture(pipeline, first, second, c);
	}

	private PipelineRun run(Pipeline pipeline, Instant started) {
		PipelineRun run = daos().pipelineRunDao().createPipelineRun(adminUuid(), pipeline.getUuid(), 1);
		run.setStarted(started);
		daos().pipelineRunDao().store(run);
		return run;
	}

	private PipelineRunItem item(PipelineRun run, int seq) {
		PipelineRunItem item = daos().pipelineRunItemDao().createRunItem(adminUuid(), run.getUuid(), seq, "/media/stats-" + seq + ".mp4");
		daos().pipelineRunItemDao().store(item);
		return item;
	}

	private void task(PipelineRunItem item, String nodeId, String kind, long durationMs, String origin) {
		PipelineNodeTask task = daos().pipelineNodeTaskDao().createNodeTask(adminUuid(), item.getUuid(), item.getRunUuid(), nodeId, kind);
		task.setState(NodeTaskState.COMPLETED);
		task.setDurationMs(durationMs);
		task.setOrigin(origin);
		daos().pipelineNodeTaskDao().store(task);
	}

	private static PipelineNodeTimeStats node(PipelineNodeStatsResponse stats, String nodeId) {
		return stats.getNodes().stream().filter(n -> n.getNodeId().equals(nodeId)).findFirst().orElseThrow();
	}

	@Test
	public void testAccumulatesOverTheLatestRuns() throws Exception {
		Fixture f = fixture();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineNodeStatsResponse stats = client.loadPipelineNodeStats(f.pipeline().getUuid(), null, null).sync().body();

			assertEquals(2, stats.getRunCount());
			assertEquals(f.second().getUuid().toString(), stats.getRuns().get(0).getUuid(), "Newest run first");
			assertEquals(83_012L, stats.getTotalDurationMs());
			assertEquals(1, stats.getReused());

			PipelineNodeTimeStats whisper = stats.getNodes().get(0);
			assertEquals("transcribe", whisper.getNodeId(), "Most expensive node first");
			assertEquals("whisper", whisper.getNodeKind());
			assertEquals(3, whisper.getTasks());
			assertEquals(2, whisper.getComputed());
			assertEquals(1, whisper.getReused());
			assertEquals(40_000L, whisper.getAvgDurationMs(), "The mean is over computed executions only");
			assertEquals(40_000L, whisper.getEstimatedSavedMs(), "One reuse saved one mean computation");
			assertEquals(50_000L, whisper.getMaxDurationMs());
			assertTrue(whisper.getShare() > 0.9);
			assertEquals(2, whisper.getRuns().size());
			assertEquals(12L, whisper.getRuns().get(0).getTotalDurationMs(), "The second run only paid for the lookup");
			assertEquals(80_000L, whisper.getRuns().get(1).getTotalDurationMs());
			assertEquals(2, whisper.getHistogram().stream().mapToLong(b -> b.getCount()).sum(), "The histogram leaves the reuse out");

			PipelineNodeTimeStats hash = node(stats, "hash");
			assertEquals(3, hash.getComputed());
			assertEquals(0L, hash.getEstimatedSavedMs());
		}
	}

	@Test
	public void testOneRunAndLimit() throws Exception {
		Fixture f = fixture();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineNodeStatsResponse one = client.loadPipelineNodeStats(f.pipeline().getUuid(), f.first().getUuid(), null).sync().body();
			assertEquals(1, one.getRunCount());
			assertEquals(0, one.getReused());
			assertEquals(80_000L, node(one, "transcribe").getTotalDurationMs());

			PipelineNodeStatsResponse latest = client.loadPipelineNodeStats(f.pipeline().getUuid(), null, 1).sync().body();
			assertEquals(1, latest.getRunCount());
			assertEquals(f.second().getUuid().toString(), latest.getRuns().get(0).getUuid());
		}
	}

	@Test
	public void testPipelineWithoutRunsIsEmpty() throws Exception {
		Pipeline pipeline = daos().pipelineDao().createPipeline(adminUuid(), "node-stats-empty-" + UUID.randomUUID());
		daos().pipelineDao().store(pipeline);
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineNodeStatsResponse stats = client.loadPipelineNodeStats(pipeline.getUuid(), null, null).sync().body();
			assertEquals(0, stats.getRunCount());
			assertTrue(stats.getNodes().isEmpty());
			assertEquals(0L, stats.getTotalDurationMs());
		}
	}

	@Test
	public void testUnknownPipelineAndForeignRunAre404() throws Exception {
		Fixture f = fixture();
		Pipeline other = daos().pipelineDao().createPipeline(adminUuid(), "node-stats-other-" + UUID.randomUUID());
		daos().pipelineDao().store(other);
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.loadPipelineNodeStats(UUID.randomUUID(), null, null));
			expect(404, "Not Found", client.loadPipelineNodeStats(other.getUuid(), f.first().getUuid(), null));
		}
	}

	/** The task records the run view reads carry the origin, which is what marks a step as reused there. */
	@Test
	public void testTaskRecordsCarryTheOrigin() throws Exception {
		Fixture f = fixture();
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PipelineNodeTaskListResponse tasks = client
				.listPipelineRunItemTasks(f.pipeline().getUuid(), f.second().getUuid(), f.secondItem().getUuid()).sync().body();
			assertEquals("REMOTE", tasks.getData().stream().filter(t -> t.getNodeId().equals("transcribe")).findFirst().orElseThrow().getOrigin());
			assertEquals("COMPUTED", tasks.getData().stream().filter(t -> t.getNodeId().equals("hash")).findFirst().orElseThrow().getOrigin());
		}
	}

	@Test
	public void testRequiresReadPipelineRun() throws Exception {
		Fixture f = fixture();
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.loadPipelineNodeStats(f.pipeline().getUuid(), null, null));
		}
		try (LoomHttpClient pipelineOnly = loginClientWith("node-stats-pipeline-reader", Permission.READ_PIPELINE)) {
			expect(403, "Forbidden", pipelineOnly.loadPipelineNodeStats(f.pipeline().getUuid(), null, null));
		}
		try (LoomHttpClient runReader = loginClientWith("node-stats-run-reader", Permission.READ_PIPELINE_RUN)) {
			assertNotNull(runReader.loadPipelineNodeStats(f.pipeline().getUuid(), null, null).sync().body());
		}
	}
}
