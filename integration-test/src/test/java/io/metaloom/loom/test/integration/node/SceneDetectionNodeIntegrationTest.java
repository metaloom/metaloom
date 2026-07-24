package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.scene.SceneDetectionNode;
import io.metaloom.cortex.node.scene.SceneDetectionOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * Integration test for {@code SceneDetectionNode}. The node runs real optical-flow scene detection (OpenCV) on a real video and writes the scene set to
 * {@code asset_segment_comp}; the test reads the segments back through REST.
 */
public class SceneDetectionNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testSceneDetectionPersistsSegments() throws Exception {
		withLoom(client -> {
			assumeVideo4j();
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");

			SceneDetectionNode node = new SceneDetectionNode(client, cortexOptions(), new SceneDetectionOptions());
			NodeResult result = node.process(NodeContext.create(media(video1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			int segments = client.listAssetSegmentComps(asset.getUuid()).sync().body().getData().size();
			assertThat(segments).as("scene segments must be readable via REST").isGreaterThanOrEqualTo(1);
		});
	}
}
