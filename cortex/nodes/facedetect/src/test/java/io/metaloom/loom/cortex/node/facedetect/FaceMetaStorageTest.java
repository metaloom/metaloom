package io.metaloom.loom.cortex.node.facedetect;

import static io.metaloom.cortex.node.facedetect.FacedetectMedia.FACE_DETECTION;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.facedetect.FacedetectMedia;
import io.metaloom.loom.cortex.node.facedetect.avro.Facedetection;

public class FaceMetaStorageTest extends AbstractFacedetectMediaTest implements FaceDataTest {

	@Test
	public void testMetaStorageIntegration() {
		FacedetectMedia media = FACE_DETECTION.wrap(mediaVideo2(), storage());
		Facedetection data = createFaceData(media.getSHA512());
		storage().put(media, FacedetectMedia.FACEDETECTION_RESULT_KEY, data);
	}

}
