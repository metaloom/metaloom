package io.metaloom.cortex.node.thumbnail;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;

/**
 * {@link ThumbnailNode}'s failure path reports FAILED and keeps the cause.
 *
 * <p>
 * It ended in {@code ctx.failure(cause).next()} until 2026-08-18, so a run that produced no
 * thumbnail at all reported SUCCESS with a null message. A review queue keyed to "assets with a
 * thumbnail" simply never filled, and nothing in the run said why.
 * </p>
 */
class ThumbnailNodeFailureTest {

	static {
		// video4j needs the OpenCV natives loaded before the first VideoCapture is constructed.
		Video4j.init();
	}

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	@Test
	void testUndecodableVideoIsFailedAndEmitsNoThumbnailPath() {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "this is not a video at all");
		StubLoomMedia media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);

		ThumbnailNode node = new ThumbnailNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new ThumbnailNodeOptions());

		assertThat(node.process(NodeContext.create(media)))
			.isFailed()
			.hasOutput(ThumbnailNode.OUT_FLAG, "FAILED")
			.hasNoOutput(ThumbnailNode.OUT_THUMBNAIL);
	}
}
