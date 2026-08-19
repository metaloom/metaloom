package io.metaloom.cortex.api.node.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;

/**
 * The terminal state {@link NodeContextImpl} builds for each of the three outcomes.
 *
 * <p>
 * The interesting case is {@code failure(cause).next()}. It used to return {@code SUCCESS} with a
 * null message, so a node that caught an exception, wrote a FAILED ledger row and ended its catch
 * block with {@code next()} reported the item as processed — the run looked identical to one where
 * the transcript, thumbnail or fingerprint had actually been produced. {@code next()} is now
 * fail-closed. Nodes are still required to spell the failure {@code abort()}
 * ({@link FailurePathGuardTest} enforces it); this is the backstop for the recording site that guard
 * cannot see.
 * </p>
 */
class NodeContextFailureTest {

	private NodeContextImpl<LoomMedia> ctx() {
		// The context never dereferences the media on any of these paths.
		return new NodeContextImpl<>((LoomMedia) null);
	}

	@Test
	void testPlainNextIsSuccess() {
		NodeResult result = ctx().next();
		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(result.getMessage()).isNull();
	}

	@Test
	void testSkippedNextIsSkippedAndKeepsTheReason() {
		NodeResult result = ctx().skipped("not applicable").next();
		assertThat(result.getState()).isEqualTo(ResultState.SKIPPED);
		assertThat(result.getMessage()).isEqualTo("not applicable");
	}

	@Test
	void testAbortIsFailedAndKeepsTheCause() {
		NodeResult result = ctx().failure("sidecar down").abort();
		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).isEqualTo("sidecar down");
	}

	@Test
	void testFailureFollowedByNextIsFailedAndKeepsTheCause() {
		NodeResult result = ctx().failure("sidecar down").next();
		assertThat(result.getState())
			.as("next() must not report a recorded failure as SUCCESS")
			.isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage())
			.as("the diagnosis must survive the terminator")
			.isEqualTo("sidecar down");
	}

	@Test
	void testAFailureOutranksASkip() {
		// A node that decided to skip and then hit a real error has failed. Reporting SKIPPED would
		// bury the error just as thoroughly as reporting SUCCESS did.
		NodeResult result = ctx().skipped("no usable regions").failure("decode failed").next();
		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).isEqualTo("decode failed");
	}
}
