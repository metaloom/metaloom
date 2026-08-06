package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.dao.PipelineFixtures.rootCauseMessage;
import static io.metaloom.loom.db.jooq.tables.JooqPipelineNodeTask.PIPELINE_NODE_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqPipelineRun.PIPELINE_RUN;
import static io.metaloom.loom.db.jooq.tables.JooqPipelineRunItem.PIPELINE_RUN_ITEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.api.pipeline.RunItemState;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.user.User;

/**
 * Every value of every pipeline vocabulary survives a write and a read, and nothing else gets past
 * the read.
 *
 * <p>
 * The three columns are {@code VARCHAR}, so the database will happily store whatever it is handed.
 * The converters on {@code pipeline_run.status}, {@code pipeline_node_task.state} and
 * {@code pipeline_run_item.state} are the only thing that stops a bad string reaching a caller -
 * which is what the rejection tests here write directly into the column to prove.
 * </p>
 *
 * <p>
 * Each test leases one database from the pool, so the vocabularies are looped over inside a test
 * rather than split across parameterised cases - a case per enum value would ask the provider for
 * twenty-odd databases to make three assertions.
 * </p>
 */
public class PipelineVocabularyDaoTest extends AbstractJooqTest {

	// ── Round trip ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Every run status round-trips through the DAO and through loadByStatus")
	public void testRunStatusRoundTrip() {
		User user = dummyUser();
		int i = 0;
		for (PipelineRunStatus status : PipelineRunStatus.values()) {
			PipelineRun run = PipelineFixtures.createRun(this, user, i++);
			run.setStatus(status);
			pipelineRunDao().update(run);

			assertEquals(status, pipelineRunDao().load(run.getUuid()).getStatus(),
				"the persisted status must come back as itself");
			// The indexed lookup has to agree, or recovery silently stops finding runs.
			assertTrue(pipelineRunDao().loadByStatus(status).stream().anyMatch(r -> run.getUuid().equals(r.getUuid())),
				"loadByStatus(" + status + ") must find the run it was just given");
		}
	}

	@Test
	@DisplayName("Every run item state round-trips, and its terminality matches loadUnfinishedByRun")
	public void testRunItemStateRoundTrip() {
		User user = dummyUser();
		int i = 0;
		for (RunItemState state : RunItemState.values()) {
			PipelineRun run = PipelineFixtures.createRun(this, user, i++);
			PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), 1, "/media/a.mp4");
			item.setState(state);
			pipelineRunItemDao().store(item);

			assertEquals(state, pipelineRunItemDao().load(item.getUuid()).getState());
			assertEquals(1, pipelineRunItemDao().countByRunAndState(run.getUuid(), state),
				"countByRunAndState(" + state + ") must count the item it was just given");
			// isTerminal() and the DAO's terminal-state set are two statements of the same rule;
			// this is what stops them drifting apart.
			assertEquals(state.isTerminal(), pipelineRunItemDao().loadUnfinishedByRun(run.getUuid()).isEmpty(),
				state + ".isTerminal() must agree with loadUnfinishedByRun");
		}
	}

	@Test
	@DisplayName("Every node task state round-trips through the DAO")
	public void testNodeTaskStateRoundTrip() {
		User user = dummyUser();
		int i = 0;
		for (NodeTaskState state : NodeTaskState.values()) {
			PipelineNodeTask task = storeTask(user, i++, state);

			assertEquals(state, pipelineNodeTaskDao().load(task.getUuid()).getState());
			assertEquals(1, pipelineNodeTaskDao().countByRunAndState(task.getRunUuid(), state),
				"countByRunAndState(" + state + ") must count the task it was just given");
		}
	}

	// ── Rejection on read ────────────────────────────────────────────────

	@Test
	@DisplayName("A run status outside the vocabulary is rejected on read, naming the column and the value")
	public void testUnknownRunStatusRejected() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);
		// Written past the converter on purpose - this is what a typo, an older writer or a
		// hand-edited row looks like on disk.
		writeRaw(PIPELINE_RUN, PIPELINE_RUN.UUID, run.getUuid(), "status", "ALMOST_DONE");

		Exception e = assertThrows(Exception.class, () -> pipelineRunDao().load(run.getUuid()));
		String message = rootCauseMessage(e);
		assertTrue(message.contains("pipeline_run.status"), "the column must be named: " + message);
		assertTrue(message.contains("ALMOST_DONE"), "the offending value must be quoted back: " + message);
	}

	@Test
	@DisplayName("A run item state outside the vocabulary is rejected on read")
	public void testUnknownRunItemStateRejected() {
		User user = dummyUser();
		PipelineRun run = PipelineFixtures.createRun(this, user, 0);
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), 1, "/media/a.mp4");
		item.setState(RunItemState.PENDING);
		pipelineRunItemDao().store(item);
		// FAILURE is what the engine's outcome enum calls it, and what used to be written here.
		writeRaw(PIPELINE_RUN_ITEM, PIPELINE_RUN_ITEM.UUID, item.getUuid(), "state", "FAILURE");

		Exception e = assertThrows(Exception.class, () -> pipelineRunItemDao().load(item.getUuid()));
		String message = rootCauseMessage(e);
		assertTrue(message.contains("pipeline_run_item.state"), "the column must be named: " + message);
		assertTrue(message.contains("FAILURE"), "the offending value must be quoted back: " + message);
	}

	@Test
	@DisplayName("A node task state outside the vocabulary is rejected on read")
	public void testUnknownNodeTaskStateRejected() {
		User user = dummyUser();
		PipelineNodeTask task = storeTask(user, 0, NodeTaskState.RUNNING);
		// DONE was in the OpenAPI example and the UI for a while; the engine never emitted it.
		writeRaw(PIPELINE_NODE_TASK, PIPELINE_NODE_TASK.UUID, task.getUuid(), "state", "DONE");

		Exception e = assertThrows(Exception.class, () -> pipelineNodeTaskDao().load(task.getUuid()));
		String message = rootCauseMessage(e);
		assertTrue(message.contains("pipeline_node_task.state"), "the column must be named: " + message);
		assertTrue(message.contains("DONE"), "the offending value must be quoted back: " + message);
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private PipelineNodeTask storeTask(User user, int i, NodeTaskState state) {
		PipelineRun run = PipelineFixtures.createRun(this, user, i);
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), 1, "/media/a.mp4");
		pipelineRunItemDao().store(item);

		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(), run.getUuid(),
			"sha512", "hash-sha512");
		task.setState(state);
		pipelineNodeTaskDao().store(task);
		return task;
	}

	/**
	 * Set a status/state column to a raw string, going around the converter.
	 *
	 * <p>
	 * Addressed by name via {@code DSL.field} because the generated field is typed as the enum,
	 * which is exactly the type this needs to sidestep.
	 * </p>
	 */
	private void writeRaw(org.jooq.Table<?> table, org.jooq.TableField<?, UUID> idField, UUID uuid,
		String column, String value) {
		context.ctx().update(table)
			.set(DSL.field(DSL.name(column), String.class), value)
			.where(idField.eq(uuid))
			.execute();
	}
}
