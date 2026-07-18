package io.metaloom.cortex.pipeline.core.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * Direct unit test of {@link CortexNodeAdapter}. The adapter is exercised
 * indirectly by every {@code *NodePipelineTest}, but the state mapping and the
 * upstream-output conversion are the seam where a wrong translation shows up as
 * a green pipeline that did nothing — so they are pinned here in isolation.
 */
class CortexNodeAdapterTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	private static CortexNodeAdapter adapt(StubFilesystemNode node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	@Test
	void testSuccessMapsToCompletedAndForwardsOutputs() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("hasher", Map.of("sha512", "abc", "bytes", 42));
		NodeResult result = adapt(node).process(MEDIA, Map.of());

		assertThat(result.getState()).isEqualTo(NodeState.COMPLETED);
		assertThat(result.getNodeId()).isEqualTo("hasher");
		assertThat(result.getOutput()).containsExactlyInAnyOrderEntriesOf(Map.of("sha512", "abc", "bytes", 42));
	}

	@Test
	void testSkippedMapsToSkipped() {
		StubFilesystemNode node = new StubFilesystemNode("hasher",
				ctx -> new io.metaloom.cortex.api.node.NodeResult(ResultState.SKIPPED));
		NodeResult result = adapt(node).process(MEDIA, Map.of());

		assertThat(result.getState()).isEqualTo(NodeState.SKIPPED);
		assertThat(result.getMessage()).isEqualTo("Node skipped");
	}

	@Test
	void testFailedMapsToFailed() {
		StubFilesystemNode node = new StubFilesystemNode("hasher",
				ctx -> new io.metaloom.cortex.api.node.NodeResult(ResultState.FAILED));
		NodeResult result = adapt(node).process(MEDIA, Map.of());

		assertThat(result.getState()).isEqualTo(NodeState.FAILED);
		assertThat(result.getMessage()).isEqualTo("Node failed");
	}

	@Test
	void testNullResultBecomesAFailureRatherThanAnNpe() {
		StubFilesystemNode node = new StubFilesystemNode("hasher", ctx -> null);
		NodeResult result = adapt(node).process(MEDIA, Map.of());

		assertThat(result.getState()).isEqualTo(NodeState.FAILED);
		assertThat(result.getMessage()).isEqualTo("Node returned null result");
	}

	@Test
	void testThrownExceptionBecomesAFailureCarryingTheMessage() {
		StubFilesystemNode node = new StubFilesystemNode("hasher", ctx -> {
			throw new IllegalStateException("native handle closed");
		});
		NodeResult result = adapt(node).process(MEDIA, Map.of());

		assertThat(result.getState()).isEqualTo(NodeState.FAILED);
		assertThat(result.getMessage()).isEqualTo("native handle closed");
	}

	@Test
	void testUpstreamResultsAreConvertedToUpstreamOutputs() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("consumer", Map.of());
		Map<String, NodeResult> upstream = Map.of(
				"md5sum", NodeResult.success("md5sum", 0, Map.of("md5", "deadbeef")),
				"tika", NodeResult.success("tika", 0, Map.of("tika_flags", "DONE")));

		adapt(node).process(MEDIA, upstream);

		assertThat(node.lastContext().upstreamOutputs())
				.containsExactlyInAnyOrderEntriesOf(Map.of(
						"md5sum", Map.of("md5", "deadbeef"),
						"tika", Map.of("tika_flags", "DONE")));
		assertThat(node.lastContext().<String> upstreamOutput("md5sum", "md5")).isEqualTo("deadbeef");
	}

	@Test
	void testNullAndEmptyUpstreamResultsConvertToAnEmptyMap() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("consumer", Map.of());
		CortexNodeAdapter adapter = adapt(node);

		adapter.process(MEDIA, null);
		assertThat(node.lastContext().upstreamOutputs()).isEmpty();

		adapter.process(MEDIA, Map.of());
		assertThat(node.lastContext().upstreamOutputs()).isEmpty();
	}

	/**
	 * A skipped upstream node contributes an entry with an empty output map
	 * rather than being dropped — downstream nodes see the node id but no values.
	 */
	@Test
	void testSkippedUpstreamResultContributesAnEmptyOutputMap() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("consumer", Map.of());
		Map<String, NodeResult> upstream = new java.util.HashMap<>();
		upstream.put("filter", NodeResult.skipped("filter", "branch not taken"));

		adapt(node).process(MEDIA, upstream);

		assertThat(node.lastContext().upstreamOutputs()).containsOnlyKeys("filter");
		assertThat(node.lastContext().upstreamOutputs().get("filter")).isEmpty();
	}

	@Test
	void testIdDefaultsToTheWrappedNodeName() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("md5", Map.of());
		CortexNodeAdapter adapter = adapt(node);

		assertThat(adapter.id()).isEqualTo("md5");
		assertThat(adapter.name()).isEqualTo("md5");
	}

	/**
	 * The id override exists because some nodes read upstream outputs under a
	 * node id that does not match the producing node's own {@code name()} — for
	 * example {@code LoomNode} reads {@code upstreamOutput("md5sum", "md5")}
	 * while {@code MD5Node.name()} is {@code "md5"}. The override must change the
	 * id the result is emitted under while leaving {@code name()} alone.
	 */
	@Test
	void testIdCanBeOverriddenIndependentlyOfTheWrappedNodeName() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("md5", Map.of("md5", "deadbeef"));
		CortexNodeAdapter adapter = new CortexNodeAdapter("md5sum", node, NodeMode.PARALLEL, true, 1);

		assertThat(adapter.id()).isEqualTo("md5sum");
		assertThat(adapter.name()).as("name still reflects the wrapped node").isEqualTo("md5");
		assertThat(adapter.process(MEDIA, Map.of()).getNodeId())
				.as("results are emitted under the overridden id")
				.isEqualTo("md5sum");
	}

	@Test
	void testInitializeDelegatesToTheWrappedNode() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("hasher", Map.of());
		CortexNodeAdapter adapter = adapt(node);

		adapter.initialize();
		assertThat(node.initializeCount()).isEqualTo(1);

		adapter.initialize();
		assertThat(node.initializeCount())
				.as("the adapter does not guard against repeated initialize — see Task 4")
				.isEqualTo(2);
	}

	@Test
	void testWrappedNodeIsExposed() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("hasher", Map.of());

		assertThat(adapt(node).getWrappedNode()).isSameAs(node);
	}

	/**
	 * {@code isSource()} tests {@code wrappedNode instanceof SourceNode}, but
	 * {@code FilesystemNode} already extends {@code SourceNode} — so every
	 * adapted node reports itself as a source. This pins the current behaviour;
	 * it is almost certainly not what the check intends.
	 */
	@Test
	void testIsSourceIsTrueForEveryAdaptedNode() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("hasher", Map.of());

		assertThat(adapt(node).isSource()).isTrue();
	}
}
