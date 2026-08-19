package io.metaloom.cortex.node.quality;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;

/**
 * The terminal state of {@link QualityNode}'s two failure paths.
 *
 * <p>
 * Both ended in {@code ctx.failure(cause).next()} until 2026-08-18, and
 * {@code NodeContextImpl.next()} read only the skip reason — so a file the node could not decode at
 * all came back as a <em>successfully measured</em> item carrying no metrics. A downstream consumer
 * of the quality metrics had no way to tell that apart from a file that genuinely had nothing to
 * report.
 * </p>
 *
 * <p>
 * Both inputs here are real: a text file wearing an image extension, and the same file wearing a
 * video one. Nothing is mocked, because the thing under test is what the node does when the decoder
 * refuses.
 * </p>
 */
class QualityNodeFailureTest {

	static {
		// The video path opens the file through video4j, which needs the OpenCV natives loaded before
		// the first VideoCapture is constructed.
		Video4j.init();
	}

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;

	@BeforeEach
	void setup() {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
	}

	private QualityNode node() {
		return new QualityNode(null, cortexOptions, new QualityNodeOptions());
	}

	private StubLoomMedia broken(String name, boolean video, boolean image) {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, name, "this is not media at all");
		StubLoomMedia media = new StubLoomMedia(backing.file().getAbsolutePath(), video, image, false, false);
		media.setSHA512(HASH);
		return media;
	}

	@Test
	void testUndecodableImageIsFailedAndSaysWhy() {
		LoomMedia media = broken("photo.jpg", false, true);

		assertThat(node().process(NodeContext.create(media)))
			.isFailed()
			.hasMessage("Could not read image file")
			.hasNoOutput(QualityNode.OUT_METRICS)
			.hasNoOutput(QualityNode.OUT_BLURRINESS);
	}

	@Test
	void testUnreadableVideoIsFailedAndSaysWhy() {
		LoomMedia media = broken("clip.mp4", true, false);

		assertThat(node().process(NodeContext.create(media)))
			.isFailed()
			.hasNoOutput(QualityNode.OUT_METRICS);
	}
}
