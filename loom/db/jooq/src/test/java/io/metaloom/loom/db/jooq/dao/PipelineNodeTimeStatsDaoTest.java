package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao.DurationBucket;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao.NodeTimeStats;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao.RunNodeTotal;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.user.User;

/**
 * Where the time of a pipeline's runs went: the aggregations behind the pipeline performance view (V2.117 adds the origin they split on).
 */
public class PipelineNodeTimeStatsDaoTest extends AbstractJooqTest {

	private int seq;

	private PipelineRunItem item(PipelineRun run) {
		PipelineRunItem item = pipelineRunItemDao().createRunItem(dummyUser().getUuid(), run.getUuid(), seq, "/media/stats-" + seq++ + ".mp4");
		pipelineRunItemDao().store(item);
		return item;
	}

	private void task(PipelineRunItem item, String nodeId, String kind, NodeTaskState state, Long durationMs, String origin) {
		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(dummyUser().getUuid(), item.getUuid(), item.getRunUuid(), nodeId, kind);
		task.setState(state);
		task.setDurationMs(durationMs);
		task.setOrigin(origin);
		pipelineNodeTaskDao().store(task);
	}

	@Test
	public void testAggregatesSplitComputedFromReused() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 8101);
		// whisper: three computed (1000/2000/3000 ms), one reused (5 ms), one failed (400 ms). NULL origin reads as computed.
		task(item(run), "transcribe", "whisper", NodeTaskState.COMPLETED, 1000L, "COMPUTED");
		task(item(run), "transcribe", "whisper", NodeTaskState.COMPLETED, 2000L, null);
		task(item(run), "transcribe", "whisper", NodeTaskState.COMPLETED, 3000L, "COMPUTED");
		task(item(run), "transcribe", "whisper", NodeTaskState.COMPLETED, 5L, "REMOTE");
		task(item(run), "transcribe", "whisper", NodeTaskState.FAILED, 400L, "COMPUTED");
		// A cheap node, and a row settled without a dispatch, which carries the placeholder kind.
		PipelineRunItem hashed = item(run);
		task(hashed, "hash", "sha512", NodeTaskState.COMPLETED, 20L, "COMPUTED");
		task(item(run), "hash", "skipped", NodeTaskState.SKIPPED, null, null);
		// Unsettled work has no duration yet and must not count.
		task(item(run), "hash", "sha512", NodeTaskState.RUNNING, null, null);

		Map<String, NodeTimeStats> stats = pipelineNodeTaskDao().aggregateByNode(List.of(run.getUuid())).stream()
			.collect(Collectors.toMap(NodeTimeStats::nodeId, Function.identity()));

		NodeTimeStats whisper = stats.get("transcribe");
		assertEquals("whisper", whisper.nodeKind());
		assertEquals(5, whisper.tasks());
		assertEquals(3, whisper.computed());
		assertEquals(1, whisper.reused());
		assertEquals(1, whisper.failed());
		assertEquals(6405L, whisper.totalMs(), "Total time counts every settled execution");
		assertEquals(6000L, whisper.computedMs());
		assertEquals(2000L, whisper.p50Ms(), "The median is over computed executions only - the 5 ms reuse must not drag it down");
		assertEquals(3000L, whisper.maxMs());

		NodeTimeStats hash = stats.get("hash");
		assertEquals("sha512", hash.nodeKind(), "The placeholder kind of a synthesised row does not hide the real one");
		assertEquals(2, hash.tasks(), "The running task is not settled");
		assertEquals(1, hash.skipped());

		List<NodeTimeStats> ordered = pipelineNodeTaskDao().aggregateByNode(List.of(run.getUuid()));
		assertEquals("transcribe", ordered.get(0).nodeId(), "Most expensive first");
	}

	@Test
	public void testHistogramUsesPowerOfTwoBucketsOverComputedExecutions() {
		PipelineRun run = PipelineFixtures.createRun(this, dummyUser(), 8102);
		task(item(run), "caption", "captioning", NodeTaskState.COMPLETED, 1L, "COMPUTED");
		task(item(run), "caption", "captioning", NodeTaskState.COMPLETED, 5L, "COMPUTED");
		task(item(run), "caption", "captioning", NodeTaskState.COMPLETED, 7L, "COMPUTED");
		task(item(run), "caption", "captioning", NodeTaskState.COMPLETED, 1500L, "COMPUTED");
		task(item(run), "caption", "captioning", NodeTaskState.COMPLETED, 3L, "LOCAL");

		Map<Integer, Long> buckets = pipelineNodeTaskDao().durationHistogram(List.of(run.getUuid())).stream()
			.filter(b -> b.nodeId().equals("caption"))
			.collect(Collectors.toMap(DurationBucket::bucket, DurationBucket::count));

		assertEquals(Map.of(0, 1L, 2, 2L, 10, 1L), buckets, "1 ms -> 0, 5 and 7 ms -> [4,8), 1500 ms -> [1024,2048); the reuse is left out");
	}

	@Test
	public void testTotalsPerRunAndNode() {
		PipelineRun first = PipelineFixtures.createRun(this, dummyUser(), 8103);
		PipelineRun second = PipelineFixtures.createRun(this, dummyUser(), 8104);
		task(item(first), "ocr", "ocr", NodeTaskState.COMPLETED, 800L, "COMPUTED");
		task(item(second), "ocr", "ocr", NodeTaskState.COMPLETED, 4L, "REMOTE");

		Map<UUID, RunNodeTotal> totals = pipelineNodeTaskDao().totalsByRunAndNode(List.of(first.getUuid(), second.getUuid())).stream()
			.collect(Collectors.toMap(RunNodeTotal::runUuid, Function.identity()));
		assertEquals(800L, totals.get(first.getUuid()).totalMs());
		assertEquals(0, totals.get(first.getUuid()).reused());
		assertEquals(4L, totals.get(second.getUuid()).totalMs());
		assertEquals(1, totals.get(second.getUuid()).reused(), "The second run reused what the first computed");
	}

	@Test
	public void testNodeWithoutComputedExecutionsHasNoPercentiles() {
		PipelineRun run = PipelineFixtures.createRun(this, dummyUser(), 8105);
		task(item(run), "tts", "tts", NodeTaskState.COMPLETED, 2L, "REMOTE");
		NodeTimeStats tts = pipelineNodeTaskDao().aggregateByNode(List.of(run.getUuid())).get(0);
		assertEquals(1, tts.reused());
		assertNull(tts.p50Ms());
		assertNull(tts.maxMs());
		assertEquals(0L, tts.computedMs());
	}

	@Test
	public void testEmptyRunListAndLatestRuns() {
		assertTrue(pipelineNodeTaskDao().aggregateByNode(List.of()).isEmpty());
		PipelineRun run = PipelineFixtures.createRun(this, dummyUser(), 8106);
		List<PipelineRun> latest = pipelineRunDao().loadLatestByPipeline(run.getPipelineUuid(), 5);
		assertEquals(1, latest.size());
		assertEquals(run.getUuid(), latest.get(0).getUuid());
		assertTrue(pipelineRunDao().loadLatestByPipeline(run.getPipelineUuid(), 0).isEmpty());
	}
}
