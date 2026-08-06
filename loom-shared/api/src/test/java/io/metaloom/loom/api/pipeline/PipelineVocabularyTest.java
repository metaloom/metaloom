package io.metaloom.loom.api.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The three pipeline vocabularies parse exactly what they document and reject everything else.
 *
 * <p>
 * The columns behind them are {@code VARCHAR}, so this is the only thing standing between a typo
 * and a status the UI cannot switch on.
 * </p>
 */
public class PipelineVocabularyTest {

	// ── parse() round trip ───────────────────────────────────────────────

	@ParameterizedTest
	@EnumSource(PipelineRunStatus.class)
	@DisplayName("Every run status survives name() -> parse()")
	void testRunStatusRoundTrip(PipelineRunStatus status) {
		assertEquals(status, PipelineRunStatus.parse("pipeline_run.status", status.name()));
	}

	@ParameterizedTest
	@EnumSource(NodeTaskState.class)
	@DisplayName("Every node task state survives name() -> parse()")
	void testNodeTaskStateRoundTrip(NodeTaskState state) {
		assertEquals(state, NodeTaskState.parse("pipeline_node_task.state", state.name()));
	}

	@ParameterizedTest
	@EnumSource(RunItemState.class)
	@DisplayName("Every run item state survives name() -> parse()")
	void testRunItemStateRoundTrip(RunItemState state) {
		assertEquals(state, RunItemState.parse("pipeline_run_item.state", state.name()));
	}

	// ── Rejection ────────────────────────────────────────────────────────

	@Test
	@DisplayName("An unrecognised status is rejected with a message naming the column and the value")
	void testUnknownRunStatusRejected() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> PipelineRunStatus.parse("pipeline_run.status", "WAT"));
		assertTrue(e.getMessage().contains("pipeline_run.status"), "the column must be named: " + e.getMessage());
		assertTrue(e.getMessage().contains("WAT"), "the offending value must be quoted back: " + e.getMessage());
		assertTrue(e.getMessage().contains("PAUSED"), "the permitted values must be listed: " + e.getMessage());
	}

	@Test
	@DisplayName("A value from the wrong vocabulary is rejected, not silently accepted")
	void testCrossVocabularyRejected() {
		// PARTIAL is a run status, never a node task state. Comparing these as strings is
		// exactly what used to make that indistinguishable.
		assertThrows(IllegalArgumentException.class,
			() -> NodeTaskState.parse("pipeline_node_task.state", "PARTIAL"));
		// DEAD_LETTER is a node task state, never an item state.
		assertThrows(IllegalArgumentException.class,
			() -> RunItemState.parse("pipeline_run_item.state", "DEAD_LETTER"));
		// FAILURE is what the engine's own outcome enum calls it; the column says FAILED.
		assertThrows(IllegalArgumentException.class,
			() -> RunItemState.parse("pipeline_run_item.state", "FAILURE"));
	}

	@Test
	@DisplayName("Parsing is case sensitive - a lowercase status is a bad value, not a near miss")
	void testCaseSensitive() {
		assertThrows(IllegalArgumentException.class,
			() -> PipelineRunStatus.parse("pipeline_run.status", "success"));
	}

	@Test
	@DisplayName("Null and blank parse to null - a row can be read before its status is set")
	void testNullAndBlank() {
		assertNull(PipelineRunStatus.parse("pipeline_run.status", null));
		assertNull(PipelineRunStatus.parse("pipeline_run.status", ""));
		assertNull(NodeTaskState.parse("pipeline_node_task.state", "  "));
	}

	// ── isTerminal() ─────────────────────────────────────────────────────

	@Test
	@DisplayName("PAUSED is not terminal - a suspended run still holds a live engine")
	void testPausedNotTerminal() {
		assertFalse(PipelineRunStatus.PAUSED.isTerminal());
	}

	@Test
	@DisplayName("Run statuses split into the four terminal ones and the three live ones")
	void testRunStatusTerminality() {
		assertTrue(PipelineRunStatus.SUCCESS.isTerminal());
		assertTrue(PipelineRunStatus.FAILED.isTerminal());
		assertTrue(PipelineRunStatus.PARTIAL.isTerminal());
		assertTrue(PipelineRunStatus.CANCELLED.isTerminal());
		assertFalse(PipelineRunStatus.PENDING.isTerminal());
		assertFalse(PipelineRunStatus.RUNNING.isTerminal());
	}

	@Test
	@DisplayName("A dead-lettered task is terminal - it is out of attempts and will not run again")
	void testNodeTaskTerminality() {
		assertTrue(NodeTaskState.COMPLETED.isTerminal());
		assertTrue(NodeTaskState.FAILED.isTerminal());
		assertTrue(NodeTaskState.SKIPPED.isTerminal());
		assertTrue(NodeTaskState.DEAD_LETTER.isTerminal());
		assertFalse(NodeTaskState.PENDING.isTerminal());
		assertFalse(NodeTaskState.RUNNING.isTerminal());
	}

	@Test
	@DisplayName("Item terminality matches the DAO's terminal-state query")
	void testRunItemTerminality() {
		assertTrue(RunItemState.SUCCESS.isTerminal());
		assertTrue(RunItemState.FAILED.isTerminal());
		assertTrue(RunItemState.SKIPPED.isTerminal());
		assertFalse(RunItemState.PENDING.isTerminal());
		assertFalse(RunItemState.RUNNING.isTerminal());
	}
}
