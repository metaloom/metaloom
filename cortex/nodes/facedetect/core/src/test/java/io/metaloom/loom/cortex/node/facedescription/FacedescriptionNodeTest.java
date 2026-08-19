package io.metaloom.loom.cortex.node.facedescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.node.facedescription.FaceDescription;
import io.metaloom.cortex.node.facedescription.FacedescriptionNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.cortex.node.facedetect.AbstractFacedetectMediaTest;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.utils.ImageUtils;

public class FacedescriptionNodeTest extends AbstractFacedetectMediaTest {

	@Test
	public void testProcessMedia() throws FileNotFoundException, IOException, LoomClientException {
		LoomMedia video = mediaVideo1();
		mockNode().process(video);
	}

	/**
	 * Ten runs rather than one because the description comes from a sampling model at
	 * {@code temperature=0.6}: a single agreeing answer proves very little about whether the prompt
	 * pins the fields it claims to pin.
	 */
	@Test
	public void testProcessImage() throws IOException, LoomClientException {
		VisionBackendAvailability.assumeRunning();
		BufferedImage image = ImageUtils.loadResource("/images/face_occluded.jpg");
		for (int i = 0; i < 10; i++) {
			FaceDescription result = mockNode().processFace(image);
			// processFace returns null after three failed model calls. Say so, rather than letting the
			// next line throw an NPE that reads like a broken assertion - the guard above has already
			// established that a backend is there, so a null here is worth reporting as itself.
			assertNotNull(result, "processFace returned null on run " + (i + 1)
				+ " - three model calls to " + VisionBackendAvailability.BASE_URL
				+ " failed; see the logged cause. Is a vision-capable model loaded?");
			assertTrue(result.getAge() > 18 && result.getAge() < 30);
			assertEquals("caucasian", result.getRace());
			assertEquals("closed", result.getMouth());
			assertEquals("open", result.getEyes());
			assertEquals("female", result.getGender());
			assertFalse(result.isNsfw());
			assertTrue(result.isFrontal());
			assertFalse(result.isProfile());
			assertTrue(result.isOccluded());
		}
	}

	public FacedescriptionNode mockNode() throws FileNotFoundException, LoomClientException {
		LoomClient client = LoomClientMock.mockClient();
		FacedetectNodeOptions option = new FacedetectNodeOptions();
		option.setInspirefacePackPath("packs/Pikachu");
		option.setMinFaceHeightFactor(0.05f).setVideoScaleSize(512);
		ObjectMapper mapper = new ObjectMapper();
		InspireFacedetector inspireface = FacedetectNodeModule.inspirefaceDetector(option);
		return new FacedescriptionNode(client, new CortexOptions(), option, mapper, inspireface);
	}
}
