package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class DuplicateFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	private static DuplicateFilterNode filter() {
		return DuplicateFilterNode.builder("dedup")
				.upstreamNodeId("sha512")
				.outputKey("sha512")
				.build();
	}

	private boolean passed(DuplicateFilterNode filter, Object identity) {
		Map<String, NodeResult> upstream = identity == null
				? upstream("sha512", Map.of())
				: upstream("sha512", Map.of("sha512", identity));
		return evaluate(filter, MEDIA, upstream).<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testBuildRequiresUpstreamNodeIdAndOutputKey() {
		assertThatThrownBy(() -> DuplicateFilterNode.builder("d").outputKey("k").build())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("upstreamNodeId and outputKey are required");

		assertThatThrownBy(() -> DuplicateFilterNode.builder("d").upstreamNodeId("n").build())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void testFirstSightingPassesAndRepeatsAreRejected() {
		DuplicateFilterNode filter = filter();

		assertThat(passed(filter, "hash-a")).as("first sighting").isTrue();
		assertThat(passed(filter, "hash-a")).as("second sighting").isFalse();
		assertThat(passed(filter, "hash-a")).as("third sighting").isFalse();
	}

	@Test
	void testDistinctIdentitiesAllPass() {
		DuplicateFilterNode filter = filter();

		assertThat(passed(filter, "hash-a")).isTrue();
		assertThat(passed(filter, "hash-b")).isTrue();
		assertThat(passed(filter, "hash-c")).isTrue();
	}

	/**
	 * The seen-set lives on the node instance and is never reset, so state leaks
	 * across every media item the node ever sees. That is the intended dedup
	 * behaviour, but it means a fresh node is required per test case.
	 */
	@Test
	void testSeenStateIsPerNodeInstance() {
		assertThat(passed(filter(), "hash-a")).isTrue();
		assertThat(passed(filter(), "hash-a")).as("a fresh node has not seen anything").isTrue();
	}

	/**
	 * Identity is derived via {@code toString()}, so values that are not equal
	 * but stringify identically collide.
	 */
	@Test
	void testIdentityIsTheStringFormOfTheValue() {
		DuplicateFilterNode filter = filter();

		assertThat(passed(filter, 5)).isTrue();
		assertThat(passed(filter, "5")).as("Integer 5 and String \"5\" share an identity").isFalse();
	}

	@Test
	void testMissingIdentityPassesAndIsNotRecorded() {
		DuplicateFilterNode filter = filter();

		assertThat(evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("no upstream results at all")
				.isTrue();
		assertThat(evaluate(filter, MEDIA, upstream("other-node", Map.of("sha512", "hash-a")))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("upstream node id does not match")
				.isTrue();
		assertThat(passed(filter, null))
				.as("output key absent")
				.isTrue();

		assertThat(passed(filter, "hash-a"))
				.as("nothing was recorded, so a real identity is still first-seen")
				.isTrue();
	}

	@Test
	void testRejectReason() {
		DuplicateFilterNode filter = filter();
		passed(filter, "hash-a");
		NodeResult result = evaluate(filter, MEDIA, upstream("sha512", Map.of("sha512", "hash-a")));

		assertThat(result.<String> getOutput("filter_reason")).isEqualTo("duplicate detected");
	}

	@Test
	void testFirstSightingRoutesToPassBranch() {
		DuplicateFilterNode filter = filter();
		FixedOutputNode hasher = new FixedOutputNode("sha512", Map.of("sha512", "hash-a"));

		PipelineResult result = route(MEDIA, filter, hasher);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("dedup", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRepeatSightingRoutesToRejectBranch() {
		DuplicateFilterNode filter = filter();
		// Prime the node so the executor run is the second sighting.
		passed(filter, "hash-a");
		FixedOutputNode hasher = new FixedOutputNode("sha512", Map.of("sha512", "hash-a"));

		PipelineResult result = route(MEDIA, filter, hasher);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("dedup", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
