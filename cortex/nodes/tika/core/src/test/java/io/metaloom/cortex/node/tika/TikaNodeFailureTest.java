package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * {@link TikaNode}'s failure path reports FAILED and names what went wrong.
 *
 * <p>
 * It ended in {@code ctx.failure("failed processing").next()} until 2026-08-18, which returned
 * SUCCESS and discarded even that message — so a document whose text was never extracted was
 * reported as extracted, with an empty result. The cause now also carries the exception message; the
 * bare constant said nothing an operator could act on.
 * </p>
 */
class TikaNodeFailureTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	@Test
	void testUnreadableSourceIsFailedAndSaysWhy() {
		// A path that exists — so the missing-file guard in AbstractMediaNode does not fire first — but
		// cannot be opened as a stream. That puts the failure inside the node's own catch block, which is
		// the line under test.
		File directory = new File(tempDir, "report.pdf");
		directory.mkdirs();

		StubLoomMedia media = new StubLoomMedia(directory.getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);

		TikaNode node = new TikaNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new TikaNodeOptions());

		assertThat(node.process(NodeContext.create(media)))
			.isFailed()
			.hasMessageContaining("failed processing")
			.hasOutput(TikaNode.OUT_FLAGS, "FAILED")
			.hasNoOutput(TikaNode.OUT_CONTENT);
	}
}
