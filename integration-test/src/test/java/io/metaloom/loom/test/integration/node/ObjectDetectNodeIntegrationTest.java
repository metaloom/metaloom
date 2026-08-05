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
import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNode;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeOptions;
import io.metaloom.cortex.node.objectdetect.ObjectDetection;
import io.metaloom.cortex.node.objectdetect.ObjectDetector;
import io.metaloom.cortex.node.objectdetect.video.VideoObjectScanner;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;

/**
 * Integration test for {@code ObjectDetectNode}. A mocked {@link ObjectDetector} stands in for the
 * YOLO native runtime + ONNX model (the detection algorithm is not under test here), while the
 * image, the {@link io.metaloom.loom.client.http.LoomHttpClient} and the Loom backend are real: the
 * detected object must reach the {@code detection} table <em>with its class label</em> and be
 * readable back through REST.
 */
public class ObjectDetectNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testObjectDetectPersistsLabelledDetections() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			ObjectDetector detector = mock(ObjectDetector.class);
			doReturn(List.of(new ObjectDetection(new BoundingBox(10, 20, 50, 60), 0.87f, 14, "person")))
				.when(detector).detect(any(BufferedImage.class));

			ObjectDetectNode node = new ObjectDetectNode(client, cortexOptions(), new ObjectDetectNodeOptions(),
				detector, new VideoObjectScanner(detector));
			NodeResult result = node.process(NodeContext.create(media(image1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			List<DetectionResponse> detections = client.listAssetDetections(asset.getUuid()).sync().body().getData();
			assertThat(detections).as("object detection must be readable via REST").isNotEmpty();

			// The label is what makes an object detection queryable at all - it is an indexed column, and
			// facedetect (the only previous writer) has never populated it. A round trip that dropped it
			// would leave the rows findable only by geometry.
			DetectionResponse written = detections.stream()
				.filter(d -> "objectdetection".equals(d.getType()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no objectdetection row was written"));
			assertThat(written.getLabel()).isEqualTo("person");
			assertThat(written.getConfidence()).isEqualTo(0.87f);
		});
	}
}
