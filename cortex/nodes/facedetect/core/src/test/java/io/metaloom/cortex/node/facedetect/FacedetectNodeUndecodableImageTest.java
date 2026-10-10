package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

/**
 * The image path of {@link FacedetectNode} on a file that is not decodable as an image.
 *
 * <p>
 * {@code ImageIO.read} answers {@code null} rather than throwing for bytes it has no reader for - a
 * truncated download, a renamed text file, a format Java does not ship a plugin for. That null used
 * to be handed straight to the detector and surfaced as a bare {@code NullPointerException} from deep
 * inside the native binding, which reads like a crash in the detector rather than a property of the
 * file. The node now fails the item cleanly, names the file, and never calls the detector.
 * </p>
 */
class FacedetectNodeUndecodableImageTest {

	@TempDir
	File tempDir;

	@Test
	void testUndecodableImageIsFailedWithoutTouchingTheDetector() {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "photo.jpg", "this is not an image at all");
		StubLoomMedia media = new StubLoomMedia(backing.file().getAbsolutePath(), false, true, false, false);

		InspireFacedetector inspireface = mock(InspireFacedetector.class);
		// Null Loom client = offline mode: the failure must come from the decode, not from a missing asset.
		FacedetectNode node = new FacedetectNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new FacedetectNodeOptions(),
			inspireface, mock(VideoFaceScanner.class));

		NodeResult result = node.process(NodeContext.create(media));

		assertThat(result)
			.isFailed()
			.hasMessageContaining("Could not decode")
			.hasMessageContaining(media.absolutePath());
		// A failed decode has nothing to report on the ports - and nothing that a later run could be served from the cache.
		assertNull(result.get(FacedetectNode.OUT_FLAG), "An undecodable image must not emit a detection flag");
		verify(inspireface, never()).detectFaces(ArgumentMatchers.<BufferedImage>any(), anyBoolean());
	}
}
