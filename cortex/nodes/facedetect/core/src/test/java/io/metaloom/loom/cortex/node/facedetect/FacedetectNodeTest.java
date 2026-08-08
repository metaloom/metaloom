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

		// One element per detected face. VideoFaceScanner#processFaces caps its output at 10, so "at least
		// 10" cannot be a strict greater-than — that asserted a count the scanner is built never to return.
		int detectionCount = result.getOutputs().get(FacedetectNode.OUT_DETECTIONS.id()).size();
		assertTrue(detectionCount >= 10, "There should be at least 10 detections. Found: " + detectionCount);

		// scalar/integer is widened to Long at the port boundary.
		Long faceCount = result.get(FacedetectNode.OUT_FACE_COUNT);
		// face_count is the number of distinct *people*, not of boxes — which is what its @PortDoc has always
		// claimed and, before clustering existed, never delivered: it emitted the detection count. Ten sampled
		// boxes of three people is three, so the two numbers not only may differ, they normally do.
		assertTrue(faceCount >= 1, "A video with faces must report at least one subject. Found: " + faceCount);
		assertTrue(faceCount <= detectionCount,
			"There cannot be more subjects than detections. Subjects: " + faceCount + ", detections: " + detectionCount);
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
