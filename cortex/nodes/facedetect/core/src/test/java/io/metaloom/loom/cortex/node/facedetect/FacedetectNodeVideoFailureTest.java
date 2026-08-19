package io.metaloom.loom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;

/**
 * The video path of {@link FacedetectNode} reports FAILED when the scan breaks.
 *
 * <p>
 * It ended in {@code ctx.failure(cause).next()} until 2026-08-18 — SUCCESS, with the cause dropped —
 * so a video that produced no detections because the scan died was indistinguishable from a video
 * with nobody in it. The sibling path a few lines up, where persisting the detections fails, already
 * aborted; the two disagreed about the same kind of outcome.
 * </p>
 *
 * <p>
 * The scanner is mocked rather than fed a broken file on purpose: a file video4j cannot open at all
 * fails earlier, in {@code AbstractMediaNode.process}, and would never reach the catch block under
 * test. Neither the detector nor a vision backend is exercised here.
 * </p>
 */
public class FacedetectNodeVideoFailureTest extends AbstractFacedetectMediaTest {

	static {
		Video4j.init();
	}

	@Test
	public void testFailedVideoScanIsFailedAndKeepsTheCause() throws Exception {
		VideoFaceScanner scanner = mock(VideoFaceScanner.class);
		when(scanner.scan(any(), anyInt(), anyBoolean())).thenThrow(new IOException("frame decode died"));

		LoomClient client = LoomClientMock.mockClient();
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		FacedetectNode node = new FacedetectNode(client, new CortexOptions(), options, mock(InspireFacedetector.class), scanner);

		LoomMedia media = mediaVideo2();

		assertThat(node.process(ctx(media)))
			.isFailed()
			.hasMessage("frame decode died")
			.hasNoOutput(FacedetectNode.OUT_FACE_COUNT)
			.hasNoOutput(FacedetectNode.OUT_DETECTIONS);
	}
}
