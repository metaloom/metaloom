package io.metaloom.loom.cortex.node.facedetect;

import static io.metaloom.cortex.node.facedetect.FacedetectMedia.FACE_DETECTION;
import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.FacedetectMedia;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.video.facedetect.dlib.DLibFacedetector;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.utils.hash.SHA512;

public class FacedetectNodeTest extends AbstractFacedetectMediaTest {

	private static final LoomMetaKey<SHA512> SHA_512_KEY = metaKey("sha512sum", 1, XATTR, SHA512.class);

	private FacedetectNode node;

	@BeforeEach
	public void setupAction() throws IOException, LoomClientException {
		node = mockNode();
		node.initialize();
	}

	@Test
	public void testVideo() throws IOException {
		FacedetectMedia media = mediaVideo2().of(FACE_DETECTION);
		NodeResult result = node.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(2).hasXAttr(SHA_512_KEY, FacedetectMedia.FACEDETECT_COUNT_KEY);
		assertTrue(media.getFaceCount() > 10, "There should be at least 10 detections. Found: " + media.getFaceCount());
	}

	@Test
	public void testImage() throws IOException, LoomClientException {
		FacedetectMedia media = mediaImage1().of(FACE_DETECTION);
		NodeResult result = node.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(1).hasXAttr(SHA_512_KEY);
		System.out.println("Faces: " + media.getFaceCount());
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
