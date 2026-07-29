package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.HashUtils;

/**
 * Pipeline integration test for {@link SHA512Node}.
 */
class SHA512NodePipelineTest extends AbstractNodeChainTest {

	@TempDir
	File tempDir;

	private String expectedSha512;
	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		media = StubLoomMedia.ofBytes(tempDir, "test-asset.bin", "sha512-pipeline-test-content");
		expectedSha512 = HashUtils.computeSHA512(media.file()).toString();
	}

	private SHA512Node createNode() {
		return createNode(true);
	}

	private SHA512Node createNode(boolean sha512Enabled) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isSHA512()).thenReturn(sha512Enabled);
		when(options.isEnabled()).thenReturn(true);
		return new SHA512Node(null, new CortexOptions(), options);
	}

	// ========================================================================
	// 1. Basic execution
	// ========================================================================

	@Test
	void testSHA512Computation() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result)
				.isSuccess()
				.hasCompletedNode("sha512")
				.hasNodeOutput("sha512", SHA512Node.OUT_HASH, expectedSha512);
	}

	@Test
	void testNodeOutput() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(media, adapter);

		assertThat(result).node("sha512")
				.isCompleted()
				.hasOutput(SHA512Node.OUT_HASH, expectedSha512)
				.hasOutputCount(1);
	}

	@Test
	void testSHA512SetsMediaHash() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		// SHA512Node sets the SHA512 on the media itself
		assertThat(media.hasSHA512()).isTrue();
		assertThat(media.getSHA512().toString()).isEqualTo(expectedSha512);
	}

	// ========================================================================
	// 2. Event dispatch
	// ========================================================================

	@Test
	void testCompletionEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent("sha512");
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(event.getMedia()).isSameAs(media);
	}

	@Test
	void testTrackingEventsDispatched() {
		CortexNodeAdapter adapter = adapt(createNode());

		execute(media, adapter);

		assertTrackingEvent("sha512", PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent("sha512", PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	// ========================================================================
	// 3. Output chaining
	// ========================================================================

	@Test
	void testOutputChaining() {
		CortexNodeAdapter sha512Adapter = adapt(createNode());
		CapturingNode consumer = new CapturingNode("consumer", SHA512Node.OUT_HASH);

		PipelineResult result = execute(media, sha512Adapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly(expectedSha512);
	}

	// ========================================================================
	// 4. Settings
	// ========================================================================

	@Test
	void testDisabledNode() {
		CortexNodeAdapter adapter = adapt(createNode(false));

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node("sha512").isSkipped();
	}

	@Test
	void testDryRunPipeline() {
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node("sha512").isSkipped();
	}

	@Test
	void testMissingFileFailsGracefully() {
		File missing = new File(tempDir, "does-not-exist.bin");
		StubLoomMedia missingMedia = StubLoomMedia.ofFile(missing);
		CortexNodeAdapter adapter = adapt(createNode());

		PipelineResult result = execute(missingMedia, adapter);

		assertThat(result).isFailed();
		assertThat(result).node("sha512").isFailed();
	}
}
