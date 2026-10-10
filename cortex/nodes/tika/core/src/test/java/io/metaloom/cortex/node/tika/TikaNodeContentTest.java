package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * The node's {@code content} port, offline.
 *
 * <p>
 * 🔴 The port was never written: the parser returned null for every file, so every consumer of {@code content} - search indexing included - received
 * nothing from a node that reported success. A test that only asserts {@code flags} cannot see that, which is why this one asserts the text.
 * </p>
 */
class TikaNodeContentTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private TikaNode node() {
		return new TikaNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new TikaNodeOptions());
	}

	private StubLoomMedia document(String name, String content) throws Exception {
		Path file = tempDir.toPath().resolve(name);
		Files.writeString(file, content, UTF_8);
		StubLoomMedia media = new StubLoomMedia(file.toAbsolutePath().toString(), false, false, false, true);
		media.setSHA512(HASH);
		return media;
	}

	@Test
	void testTheExtractedTextReachesTheContentPort() throws Exception {
		StubLoomMedia media = document("report.txt", "Quarterly numbers are inside this document.");

		assertThat(node().process(NodeContext.create(media)))
			.isSuccess()
			.hasOutput(TikaNode.OUT_FLAGS, "DONE")
			.hasOutput(TikaNode.OUT_CONTENT, "Quarterly numbers are inside this document.");
	}

	/**
	 * A file with no text is a successful run with nothing to say. The port stays unwritten rather than delivering {@code ""}, which a downstream text
	 * node would otherwise process as a document.
	 */
	@Test
	void testAFileWithoutTextLeavesTheContentPortUnwritten() throws Exception {
		StubLoomMedia media = document("blank.txt", "\n\n");

		assertThat(node().process(NodeContext.create(media)))
			.isSuccess()
			.hasOutput(TikaNode.OUT_FLAGS, "DONE")
			.hasNoOutput(TikaNode.OUT_CONTENT);
	}

	/**
	 * The second pass is served from the in-heap cache, and must emit exactly what the first one did - a cache that re-emits nothing is how a re-run over
	 * a corpus silently produces less than the first run.
	 */
	@Test
	void testACachedResultIsReEmitted() throws Exception {
		StubLoomMedia media = document("report.txt", "Quarterly numbers are inside this document.");
		TikaNode node = node();

		node.process(NodeContext.create(media));

		assertThat(node.process(NodeContext.create(media)))
			.isSuccess()
			.hasOutput(TikaNode.OUT_CONTENT, "Quarterly numbers are inside this document.");
	}
}
