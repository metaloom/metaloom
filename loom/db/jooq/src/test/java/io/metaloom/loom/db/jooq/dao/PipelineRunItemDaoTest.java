package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static io.metaloom.loom.db.jooq.dao.PipelineFixtures.rootCauseMessage;

import io.metaloom.loom.api.pipeline.RunItemState;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.user.User;

public class PipelineRunItemDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineRunItemDao, PipelineRunItem> {

	@Override
	public PipelineRunItemDao getDao() {
		return pipelineRunItemDao();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	@Override
	public PipelineRunItem createElement(User user, int i) {
		PipelineRun run = PipelineFixtures.createRun(this, user, i);
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), i, "/media/file-" + i + ".mp4");
		item.setSizeBytes(1024L + i);
		item.setState(RunItemState.PENDING);
		return item;
	}

	@Override
	public void assertCreate(PipelineRunItem created) {
		assertNotNull(created.getRunUuid());
		assertEquals(0, created.getItemSeq());
		assertEquals("/media/file-0.mp4", created.getMediaPath());
		assertEquals(1024L, created.getSizeBytes());
		assertEquals(RunItemState.PENDING, created.getState());
		assertNull(created.getSha512(), "The source does not hash - that is a node's job");
	}

	@Override
	public void updateElement(PipelineRunItem element) {
		element.setState(RunItemState.SUCCESS);
		element.setSha512("abc123");
		element.setErrorMessage(null);
	}

	@Override
	public void assertUpdate(PipelineRunItem updated) {
		assertEquals(RunItemState.SUCCESS, updated.getState());
		assertEquals("abc123", updated.getSha512());
	}

	@Test
	public void testItemSeqIsUniquePerRun() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);

		PipelineRunItem first = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), 7, "/media/a.mp4");
		pipelineRunItemDao().store(first);

		// A source that reconnects and replays a batch must not double-insert. The
		// unique constraint is the only thing standing between a retry and a run
		// whose item count silently doubles.
		PipelineRunItem duplicate = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), 7, "/media/a.mp4");
		Exception thrown = assertThrows(Exception.class, () -> pipelineRunItemDao().store(duplicate),
			"A second item with the same sequence in the same run must be rejected");

		// Naming the constraint keeps this from passing on any random failure.
		assertTrue(rootCauseMessage(thrown).contains("pipeline_run_item_unique_seq"),
			"Expected the sequence constraint to reject it, got: " + rootCauseMessage(thrown));
	}

	@Test
	public void testSameSeqIsAllowedInDifferentRuns() {
		User user = dummyUser();
		PipelineRun runA = PipelineFixtures.createRun(this, user, 0);
		PipelineRun runB = PipelineFixtures.createRun(this, user, 1);

		pipelineRunItemDao().store(pipelineRunItemDao().createRunItem(user.getUuid(), runA.getUuid(), 1, "/media/a.mp4"));
		pipelineRunItemDao().store(pipelineRunItemDao().createRunItem(user.getUuid(), runB.getUuid(), 1, "/media/a.mp4"));

		assertEquals(1, pipelineRunItemDao().loadByRun(runA.getUuid()).size());
		assertEquals(1, pipelineRunItemDao().loadByRun(runB.getUuid()).size());
	}

	@Test
	public void testLoadByRunIsOrderedByDiscovery() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);

		// Insert out of order to prove the ordering comes from the query, not from
		// insertion order.
		for (long seq : new long[] { 3, 1, 2 }) {
			pipelineRunItemDao().store(pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), seq, "/media/" + seq + ".mp4"));
		}

		List<PipelineRunItem> items = pipelineRunItemDao().loadByRun(run.getUuid());
		assertEquals(3, items.size());
		assertEquals(1, items.get(0).getItemSeq());
		assertEquals(2, items.get(1).getItemSeq());
		assertEquals(3, items.get(2).getItemSeq());
	}

	@Test
	public void testLoadUnfinishedExcludesTerminalItems() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);

		storeItemInState(user, run.getUuid(), 1, RunItemState.PENDING);
		storeItemInState(user, run.getUuid(), 2, RunItemState.RUNNING);
		storeItemInState(user, run.getUuid(), 3, RunItemState.SUCCESS);
		storeItemInState(user, run.getUuid(), 4, RunItemState.FAILED);
		storeItemInState(user, run.getUuid(), 5, RunItemState.SKIPPED);

		List<PipelineRunItem> unfinished = pipelineRunItemDao().loadUnfinishedByRun(run.getUuid());

		// Recovery resumes exactly these. Including a terminal item would re-run work
		// that is already decided; excluding a RUNNING one would strand it forever.
		assertEquals(2, unfinished.size(), "Only PENDING and RUNNING are resumable");
		assertTrue(unfinished.stream().allMatch(i -> List.of(RunItemState.PENDING, RunItemState.RUNNING).contains(i.getState())));
	}

	@Test
	public void testCountByRunAndState() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);

		storeItemInState(user, run.getUuid(), 1, RunItemState.SUCCESS);
		storeItemInState(user, run.getUuid(), 2, RunItemState.SUCCESS);
		storeItemInState(user, run.getUuid(), 3, RunItemState.FAILED);

		assertEquals(2, pipelineRunItemDao().countByRunAndState(run.getUuid(), RunItemState.SUCCESS));
		assertEquals(1, pipelineRunItemDao().countByRunAndState(run.getUuid(), RunItemState.FAILED));
		assertEquals(0, pipelineRunItemDao().countByRunAndState(run.getUuid(), RunItemState.PENDING));
	}

	@Test
	public void testDeletingARunCascadesToItsItems() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);
		UUID runUuid = run.getUuid();
		storeItemInState(user, runUuid, 1, RunItemState.PENDING);

		pipelineRunDao().delete(runUuid);

		// Without the cascade, deleting run history would leave orphaned item rows
		// that nothing can reach or clean up.
		assertEquals(0, pipelineRunItemDao().loadByRun(runUuid).size());
	}

	private void storeItemInState(User user, UUID runUuid, long seq, RunItemState state) {
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), runUuid, seq, "/media/" + seq + ".mp4");
		item.setState(state);
		pipelineRunItemDao().store(item);
	}

}
