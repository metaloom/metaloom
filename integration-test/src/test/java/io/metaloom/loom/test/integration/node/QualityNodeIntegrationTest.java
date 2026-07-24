package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.quality.QualityNode;
import io.metaloom.cortex.node.quality.QualityNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

/**
 * Integration test for {@code QualityNode}. The node computes real quality metrics (resolution, blurriness via OpenCV) on a real image and writes a
 * {@code quality} JSON component; the test reads it back through REST.
 */
public class QualityNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testQualityPersistsJsonComp() throws Exception {
		withLoom(client -> {
			assumeVideo4j();
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			QualityNode node = new QualityNode(client, cortexOptions(), new QualityNodeOptions());
			NodeResult result = node.process(NodeContext.create(media(image1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> "quality".equals(c.getSchemaType()))
				.findFirst().orElse(null);
			assertThat(comp).as("quality JSON component must be readable via REST").isNotNull();
			assertThat(comp.getData()).as("quality component must carry metrics").isNotNull();
		});
	}
}
