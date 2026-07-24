package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.facedetect.FacedetectNode;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

/**
 * Integration test for {@code FacedetectNode}. A mocked {@link InspireFacedetector} stands in for the InspireFace native runtime + model (the detection
 * algorithm is not under test here), while the image, the {@link io.metaloom.loom.client.http.LoomHttpClient} and the Loom backend are real: the detected
 * face must reach the {@code detection} table and be readable back through REST.
 */
public class FacedetectNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testFacedetectPersistsDetections() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			Face face = Face.create(FaceBox.create(10, 20, 50, 60));
			InspireFacedetector detector = mock(InspireFacedetector.class);
			doReturn(List.of(face)).when(detector).detectFaces(any(BufferedImage.class));
			VideoFaceScanner scanner = mock(VideoFaceScanner.class);

			FacedetectNode node = new FacedetectNode(client, cortexOptions(), new FacedetectNodeOptions(), detector, scanner);
			NodeResult result = node.process(NodeContext.create(media(image1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			int detections = client.listAssetDetections(asset.getUuid()).sync().body().getData().size();
			assertThat(detections).as("face detection must be readable via REST").isGreaterThanOrEqualTo(1);
		});
	}
}
