package io.metaloom.cortex.pipeline.core.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;

/**
 * Direct unit test of {@link CortexNodeAdapter}. The adapter is exercised
 * indirectly by every {@code *NodePipelineTest}, but the state mapping and the
 * hand-over of the port view are the seam where a wrong translation shows up as
 * a green pipeline that did nothing — so they are pinned here in isolation.
 */
class CortexNodeAdapterTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	private static final OutputPort<String> OUT_SHA512 =
		OutputPort.one("hash", ContentTypeRegistry.HASH_SHA512, String.class);
	private static final OutputPort<Long> OUT_BYTES =
		OutputPort.one("bytes", ContentTypeRegistry.SCALAR_INTEGER, Long.class);
	private static final InputPort<String> IN_MD5 =
		InputPort.one("md5", ContentTypeRegistry.HASH_MD5, String.class);
	private static final InputPort<String> IN_FLAGS =
		InputPort.one("flags", ContentTypeRegistry.SCALAR_STRING, String.class);

	private static CortexNodeAdapter adapt(StubFilesystemNode node) {
		return new CortexNodeAdapter(node, NodeMode.PARALLEL, true, 1);
	}

	@Test
	void testSuccessMapsToCompletedAndForwardsOutputs() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("hasher", Map.of(
				OUT_SHA512.id(), PortOutput.one(OUT_SHA512, "abc"),
				OUT_BYTES.id(), PortOutput.one(OUT_BYTES, 42L)));
		NodeResult result = adapt(node).process(MEDIA, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(result.getNodeId()).isEqualTo("hasher");
		assertThat(result.getOutputs()).containsOnlyKeys(OUT_SHA512.id(), OUT_BYTES.id());
		assertThat(result.get(OUT_SHA512)).isEqualTo("abc");
		assertThat(result.get(OUT_BYTES)).isEqualTo(42L);
	}

	@Test
	void testSkippedMapsToSkipped() {
		// Node and pipeline results are the same type now: the adapter stamps identity + timing and preserves the node's own state and message (skip reason).
		StubFilesystemNode node = new StubFilesystemNode("hasher",
				ctx -> new io.metaloom.cortex.api.node.NodeResult(null, ResultState.SKIPPED, 0, "unprocessable", java.util.Map.of()));
		NodeResult result = adapt(node).process(MEDIA, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.SKIPPED);
		assertThat(result.getNodeId()).isEqualTo("hasher");
		assertThat(result.getMessage()).isEqualTo("unprocessable");
	}

	@Test
	void testFailedMapsToFailed() {
		StubFilesystemNode node = new StubFilesystemNode("hasher",
				ctx -> new io.metaloom.cortex.api.node.NodeResult(null, ResultState.FAILED, 0, "boom", java.util.Map.of()));
		NodeResult result = adapt(node).process(MEDIA, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getNodeId()).isEqualTo("hasher");
		assertThat(result.getMessage()).isEqualTo("boom");
	}

	@Test
	void testNullResultBecomesAFailureRatherThanAnNpe() {
		StubFilesystemNode node = new StubFilesystemNode("hasher", ctx -> null);
		NodeResult result = adapt(node).process(MEDIA, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).isEqualTo("Node returned null result");
	}

	@Test
	void testThrownExceptionBecomesAFailureCarryingTheMessage() {
		StubFilesystemNode node = new StubFilesystemNode("hasher", ctx -> {
			throw new IllegalStateException("native handle closed");
		});
		NodeResult result = adapt(node).process(MEDIA, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.getMessage()).isEqualTo("native handle closed");
	}

	@Test
	void testPortViewReachesTheWrappedNodeUnchanged() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("consumer", Map.of());
		NodeInputs inputs = NodeInputs.builder()
				.input(IN_MD5, "deadbeef")
				.input(IN_FLAGS, "DONE")
				.build();

		adapt(node).process(MEDIA, inputs);

		assertThat(node.lastContext().input(IN_MD5)).isEqualTo("deadbeef");
		assertThat(node.lastContext().input(IN_FLAGS)).isEqualTo("DONE");
		assertThat(node.lastContext().isWired(IN_MD5)).isTrue();
	}

	/**
	 * A node invoked with nothing wired must still run: an unwired optional port reads
	 * as null rather than as a missing map the node has to defend against.
	 */
	@Test
	void testNullInputsBecomeAnEmptyPortView() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("consumer", Map.of());
		CortexNodeAdapter adapter = adapt(node);

		adapter.process(MEDIA, null);
		assertThat(node.lastContext().input(IN_MD5)).isNull();
		assertThat(node.lastContext().isWired(IN_MD5)).isFalse();

		adapter.process(MEDIA, NodeInputs.empty());
		assertThat(node.lastContext().input(IN_MD5)).isNull();
	}

	@Test
	void testIdDefaultsToTheWrappedNodeName() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("md5", Map.of());
		CortexNodeAdapter adapter = adapt(node);

		assertThat(adapter.id()).isEqualTo("md5");
		assertThat(adapter.name()).isEqualTo("md5");
	}

	/**
	 * The id override used to exist because some nodes read upstream outputs under a
	 * node id that did not match the producing node's own {@code name()}. Nothing
	 * addresses data by node id any more, but a graph may still place two instances of
	 * one kind, so the override must change the id a result is emitted under while
	 * leaving {@code name()} alone.
	 */
	@Test
	void testIdCanBeOverriddenIndependentlyOfTheWrappedNodeName() {
		StubFilesystemNode node = StubFilesystemNode.succeeding("md5", Map.of());
		CortexNodeAdapter adapter = new CortexNodeAdapter("md5sum", node, NodeMode.PARALLEL, true, 1);

		assertThat(adapter.id()).isEqualTo("md5sum");
		assertThat(adapter.name()).as("name still reflects the wrapped node").isEqualTo("md5");
		assertThat(adapter.process(MEDIA, NodeInputs.empty()).getNodeId())
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
