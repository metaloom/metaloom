package io.metaloom.cortex.node.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;

/**
 * Test for the {@link HelloWorldNode} showing how to verify a custom node.
 */
public class HelloWorldNodeTest {

	private HelloWorldNode node;
	private CortexOptions cortexOptions;

	@TempDir
	Path tempDir;

	@BeforeEach
	public void setup() throws IOException {
		cortexOptions = new CortexOptions();
		cortexOptions.setMetaPath(tempDir);
		HelloWorldNodeOptions options = new HelloWorldNodeOptions();
		// null client = offline mode (no Loom server needed)
		node = new HelloWorldNode(null, cortexOptions, options);
	}

	@Test
	public void testName() {
		assertEquals("hello-world", node.name());
	}

	@Test
	public void testProcessTextFile() throws IOException {
		// Create a temp file with known content
		Path textFile = tempDir.resolve("hello.txt");
		Files.writeString(textFile, "Hello World from Cortex\nThis is a test file\n");

		LoomMedia media = new LoomMediaImpl(textFile);
		NodeContext<LoomMedia> ctx = NodeContext.create(media);

		NodeResult result = node.process(ctx);

		assertEquals(ResultState.SUCCESS, result.getState());

		// Verify both output ports carry a value for downstream nodes
		long fileSize = result.get(HelloWorldNode.OUT_FILE_SIZE);
		long wordCount = result.get(HelloWorldNode.OUT_WORD_COUNT);
		assertTrue(fileSize > 0, "File size should be > 0");
		assertEquals(9, wordCount, "Expected 9 words in the test file");
	}

	@Test
	public void testProcessBinaryFile() throws IOException {
		// Create a file with binary content (word count should be 0)
		Path binFile = tempDir.resolve("data.bin");
		byte[] binary = new byte[] { 0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE };
		Files.write(binFile, binary);

		LoomMedia media = new LoomMediaImpl(binFile);
		NodeContext<LoomMedia> ctx = NodeContext.create(media);

		NodeResult result = node.process(ctx);

		assertEquals(ResultState.SUCCESS, result.getState());
		long fileSize = result.get(HelloWorldNode.OUT_FILE_SIZE);
		assertEquals(6, fileSize);
	}

	@Test
	public void testInputPort() throws IOException {
		Path textFile = tempDir.resolve("upstream.txt");
		Files.writeString(textFile, "test");

		LoomMedia media = new LoomMediaImpl(textFile);

		// Seed the "hash" input port the way a wired edge would. The upstream node id is not
		// mentioned anywhere - the port is the seam.
		NodeInputs inputs = NodeInputs.builder()
			.input(HelloWorldNode.IN_HASH, "abc123def456")
			.build();

		NodeContext<LoomMedia> ctx = NodeContext.create(media, inputs);
		NodeResult result = node.process(ctx);

		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(1L, result.get(HelloWorldNode.OUT_WORD_COUNT));
	}

	@Test
	public void testDisabled() throws IOException {
		// Disable the node via options
		HelloWorldNodeOptions disabledOptions = new HelloWorldNodeOptions();
		disabledOptions.setEnabled(false);
		HelloWorldNode disabledNode = new HelloWorldNode(null, cortexOptions, disabledOptions);

		Path textFile = tempDir.resolve("disabled.txt");
		Files.writeString(textFile, "should not be processed");

		LoomMedia media = new LoomMediaImpl(textFile);
		NodeContext<LoomMedia> ctx = NodeContext.create(media);

		NodeResult result = disabledNode.process(ctx);
		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	public void testMissingFile() {
		Path missing = tempDir.resolve("does-not-exist.txt");
		LoomMedia media = new LoomMediaImpl(missing);
		NodeContext<LoomMedia> ctx = NodeContext.create(media);

		NodeResult result = node.process(ctx);
		assertEquals(ResultState.FAILED, result.getState());
	}
}
