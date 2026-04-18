package io.metaloom.loom.cortex.node.facedetect;

import static io.metaloom.cortex.node.facedetect.FacedetectionMetaStorage.FACEDETECTION_RESULT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.node.facedetect.FacedetectionMetaStorage;
import io.metaloom.cortex.api.media.type.LoomMetaTypeHandler;
import io.metaloom.cortex.api.media.type.handler.impl.AvroLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.media.type.handler.impl.XAttrLoomMetaTypeHandlerImpl;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.cortex.common.meta.MetaStorageImpl;
import io.metaloom.loom.cortex.node.facedetect.avro.Facedetection;
import io.metaloom.loom.cortex.node.facedetect.avro.FacedetectionBox;

public class FacedetectMediaTest extends AbstractFacedetectMediaTest {

	@Test
	public void testFaceCount() throws IOException {
		LoomMedia media = mediaVideo2();
		FacedetectionMetaStorage faceStorage = faceStorage();
		assertNull(faceStorage.getFaceCount(media));
		faceStorage.setFaceCount(media, 42);
		assertEquals(42, faceStorage.getFaceCount(media));

		// Verify via a new storage instance (simulating reload)
		FacedetectionMetaStorage faceStorage2 = faceStorage();
		assertEquals(42, faceStorage2.getFaceCount(media));
	}

	@Test
	public void testFaceDetectionParameters() throws IOException {
		LoomMedia media = mediaVideo2();
		FacedetectionMetaStorage faceStorage = faceStorage();
		assertNull(faceStorage.getFacedetections(media));
		assertNull(storage().get(media, FACEDETECTION_RESULT_KEY));

		FacedetectionBox box = FacedetectionBox.newBuilder()
			.setStartX(0)
			.setStartY(0)
			.setHeight(10)
			.setWidth(10)
			.build();

		Facedetection facedetection = Facedetection.newBuilder()
			.setAssetHash(media.getSHA512().toString())
			.setFrame(10)
			.setBox(box)
			.build();
		faceStorage.appendFacedetection(media, facedetection);
		assertEquals(2, faceStorage.getFacedetections(media).size());

		// Verify via a new storage/media instance (simulating reload)
		LoomMedia media2 = media(media.path());
		FacedetectionMetaStorage faceStorage2 = faceStorage();
		assertNotNull(faceStorage2.getFacedetections(media2));
		Facedetection params = storage().get(media2, FACEDETECTION_RESULT_KEY);
		assertNotNull(params);

		// Now append another detection
		faceStorage2.appendFacedetection(media2, params);
	}

	FacedetectionMetaStorage faceStorage() {
		return new FacedetectionMetaStorage(storage());
	}

	@Override
	public MetaStorage storage() {
		Set<LoomMetaTypeHandler> handlers = Set.of(new AvroLoomMetaTypeHandlerImpl(cortexOptions), new XAttrLoomMetaTypeHandlerImpl());
		MetaStorage storage = new MetaStorageImpl(handlers);
		return storage;
	}

}
