package io.metaloom.loom.cortex.node.facedetect;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.node.facedetect.FacedetectionMetaStorage;
import io.metaloom.loom.cortex.node.facedetect.avro.Facedetection;

public class FaceMetaStorageTest extends AbstractFacedetectMediaTest implements FaceDataTest {

	@Test
	public void testMetaStorageIntegration() {
		LoomMedia media = mediaVideo2();
		FacedetectionMetaStorage faceStorage = new FacedetectionMetaStorage(storage());
		Facedetection data = createFaceData(media.getSHA512());
		faceStorage.appendFacedetection(media, data);
	}

}
