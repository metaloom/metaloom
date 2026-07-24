package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.tika.TikaNode;
import io.metaloom.cortex.node.tika.TikaNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

/**
 * Integration test for {@code TikaNode}. Apache Tika (a bundled Java library) extracts metadata from a real document and the node writes it as a {@code tika}
 * JSON component; the test reads the component back through the REST API.
 */
public class TikaNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testTikaPersistsJsonComp() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, docDOCX(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

			TikaNode node = new TikaNode(client, cortexOptions(), new TikaNodeOptions());
			NodeResult result = node.process(NodeContext.create(media(docDOCX())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> "tika".equals(c.getSchemaType()))
				.findFirst().orElse(null);
			assertThat(comp).as("tika JSON component must be readable via REST").isNotNull();
			assertThat(comp.getNodeKind()).isEqualTo("tika");
			assertThat(comp.getData()).as("tika component must carry extracted data").isNotNull();
		});
	}
}
