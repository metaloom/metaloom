package io.metaloom.loom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

public class FacedetectNodeTest extends AbstractFacedetectMediaTest {

	private FacedetectNode node;

	@BeforeEach
	public void setupAction() throws IOException, LoomClientException {
		node = mockNode();
		node.initialize();
	}

	@Test
	public void testVideo() throws IOException {
		LoomMedia media = mediaVideo2();
		NodeResult result = node.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(result).hasOutput(FacedetectNode.OUT_FACE_COUNT);
		// scalar/integer is widened to Long at the port boundary.
		Long faceCount = result.get(FacedetectNode.OUT_FACE_COUNT);
		assertTrue(faceCount > 10, "There should be at least 10 detections. Found: " + faceCount);
		// The count is derived from the element sequence, so the two can never disagree.
		assertThat(result).hasElementCount(FacedetectNode.OUT_DETECTIONS, faceCount.intValue());
	}

	@Test
	public void testImage() throws IOException, LoomClientException {
		LoomMedia media = mediaImage1();
		NodeResult result = node.process(ctx(media));
		assertThat(result).isSuccess();
		System.out.println("Faces: " + result.get(FacedetectNode.OUT_FACE_COUNT));
	}

	public FacedetectNode mockNode() throws FileNotFoundException, LoomClientException {
		LoomClient client = LoomClientMock.mockClient();
		FacedetectNodeOptions option = new FacedetectNodeOptions();
		option.setInspirefacePackPath("packs/Pikachu");
		option.setMinFaceHeightFactor(0.05f).setVideoScaleSize(512);
//		DLibFacedetector dlib = FacedetectNodeModule.dlibDetector(option);
		InspireFacedetector inspireface = FacedetectNodeModule.inspirefaceDetector(option);
		VideoFaceScanner videoScanner = new VideoFaceScanner(inspireface);
		return new FacedetectNode(client, new CortexOptions(), option, inspireface, videoScanner);
	}

}
