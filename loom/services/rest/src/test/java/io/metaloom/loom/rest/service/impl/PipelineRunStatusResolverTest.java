package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.rest.service.impl.PipelineRunStatusResolver.CANCELLED;
import static io.metaloom.loom.rest.service.impl.PipelineRunStatusResolver.FAILED;
import static io.metaloom.loom.rest.service.impl.PipelineRunStatusResolver.PARTIAL;
import static io.metaloom.loom.rest.service.impl.PipelineRunStatusResolver.PENDING;
import static io.metaloom.loom.rest.service.impl.PipelineRunStatusResolver.RUNNING;
import static io.metaloom.loom.rest.service.impl.PipelineRunStatusResolver.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the counters -> {@code pipeline_run.status} mapping. Kept free
 * of DB and transport concerns so the decision table is verifiable on its own.
 */
public class PipelineRunStatusResolverTest {

	// ── resolve() ────────────────────────────────────────────────────────

	@Test
	@DisplayName("A run with no failures is SUCCESS")
	void testAllSucceeded() {
		assertThat(PipelineRunStatusResolver.resolve(10, 0)).isEqualTo(SUCCESS);
	}

	@Test
	@DisplayName("A run where every media item failed is FAILED")
	void testAllFailed() {
		assertThat(PipelineRunStatusResolver.resolve(10, 10)).isEqualTo(FAILED);
	}

	@Test
	@DisplayName("A run with a mix of failures and non-failures is PARTIAL")
	void testSomeFailed() {
		assertThat(PipelineRunStatusResolver.resolve(10, 1)).isEqualTo(PARTIAL);
		assertThat(PipelineRunStatusResolver.resolve(10, 5)).isEqualTo(PARTIAL);
		assertThat(PipelineRunStatusResolver.resolve(10, 9)).isEqualTo(PARTIAL);
	}

	@Test
	@DisplayName("A single failing media item is FAILED, not PARTIAL")
	void testSingleItemFailed() {
		assertThat(PipelineRunStatusResolver.resolve(1, 1)).isEqualTo(FAILED);
	}

	@Test
	@DisplayName("A run that processed nothing is SUCCESS — there was nothing to fail")
	void testEmptyRun() {
		assertThat(PipelineRunStatusResolver.resolve(0, 0)).isEqualTo(SUCCESS);
	}

	@Test
	@DisplayName("A dry run where everything was skipped is SUCCESS")
	void testAllSkipped() {
		// Skipped items are not failures, so the run succeeded.
		assertThat(PipelineRunStatusResolver.resolve(7, 0)).isEqualTo(SUCCESS);
	}

	@Test
	@DisplayName("More failures than media reported is treated as FAILED, not PARTIAL")
	void testInconsistentCountersFailClosed() {
		// A malformed report must not produce an optimistic status.
		assertThat(PipelineRunStatusResolver.resolve(3, 5)).isEqualTo(FAILED);
		assertThat(PipelineRunStatusResolver.resolve(0, 2)).isEqualTo(FAILED);
	}

	@Test
	@DisplayName("Negative counters are clamped rather than producing a bogus status")
	void testNegativeCountersClamped() {
		assertThat(PipelineRunStatusResolver.resolve(-1, -1)).isEqualTo(SUCCESS);
		assertThat(PipelineRunStatusResolver.resolve(5, -3)).isEqualTo(SUCCESS);
	}

	// ── isTerminal() ─────────────────────────────────────────────────────

	@Test
	@DisplayName("SUCCESS, FAILED, PARTIAL and CANCELLED are terminal")
	void testTerminalStates() {
		assertThat(PipelineRunStatusResolver.isTerminal(SUCCESS)).isTrue();
		assertThat(PipelineRunStatusResolver.isTerminal(FAILED)).isTrue();
		assertThat(PipelineRunStatusResolver.isTerminal(PARTIAL)).isTrue();
		assertThat(PipelineRunStatusResolver.isTerminal(CANCELLED)).isTrue();
	}

	@Test
	@DisplayName("PENDING and RUNNING are not terminal")
	void testNonTerminalStates() {
		assertThat(PipelineRunStatusResolver.isTerminal(PENDING)).isFalse();
		assertThat(PipelineRunStatusResolver.isTerminal(RUNNING)).isFalse();
	}

	@Test
	@DisplayName("An unknown or null status is not terminal, so it can still be closed out")
	void testUnknownStatusNotTerminal() {
		assertThat(PipelineRunStatusResolver.isTerminal(null)).isFalse();
		assertThat(PipelineRunStatusResolver.isTerminal("")).isFalse();
		assertThat(PipelineRunStatusResolver.isTerminal("WAT")).isFalse();
	}
}
