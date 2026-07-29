package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

class DuplicateFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	/**
	 * Stand-in for whichever hashing node feeds the filter. Its id is what matters:
	 * the filter binds by port id ({@code hash}), not by the producer's node id, so a
	 * sha512 node and a fingerprint node are interchangeable here.
	 */
	private static final OutputPort<String> OUT_SHA512 =
		OutputPort.one(DuplicateFilterNode.IN_HASH.id(), ContentTypeRegistry.HASH_SHA512, String.class);

	private static DuplicateFilterNode filter() {
		return DuplicateFilterNode.builder("dedup").build();
	}

	private boolean passed(DuplicateFilterNode filter, String identity) {
		return passed(evaluate(filter, MEDIA, input(DuplicateFilterNode.IN_HASH, identity)));
	}

	private static FixedOutputNode hasher(String identity) {
		return new FixedOutputNode("sha512", Map.of(OUT_SHA512.id(), PortOutput.one(OUT_SHA512, identity)));
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
	 * An unwired hash port means the filter cannot dedup at all. It fails open and,
	 * crucially, records nothing — otherwise the first real identity to arrive would
	 * be treated as a repeat of "no identity".
	 */
	@Test
	void testMissingIdentityPassesAndIsNotRecorded() {
		DuplicateFilterNode filter = filter();

		assertThat(passed(evaluate(filter, MEDIA)))
				.as("nothing wired into the hash port")
				.isTrue();
		assertThat(passed(evaluate(filter, MEDIA)))
				.as("still nothing to dedup on")
				.isTrue();

		assertThat(passed(filter, "hash-a"))
				.as("nothing was recorded, so a real identity is still first-seen")
				.isTrue();
	}

	@Test
	void testFirstSightingRoutesToPassBranch() {
		DuplicateFilterNode filter = filter();

		PipelineResult result = route(MEDIA, filter, hasher("hash-a"));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("dedup", AbstractFilterNode.OUT_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRepeatSightingRoutesToRejectBranch() {
		DuplicateFilterNode filter = filter();
		// Prime the node so the pipeline run is the second sighting.
		passed(filter, "hash-a");

		PipelineResult result = route(MEDIA, filter, hasher("hash-a"));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("dedup", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
