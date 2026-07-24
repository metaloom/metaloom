package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.thumbnail.ThumbnailNode;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

/**
 * Integration test for {@code ThumbnailNode}. The node renders a real contact-sheet thumbnail (video4j/OpenCV) into the local thumbnail cache and records a
 * node-result ledger row (the bytes stay local by design); the test reads the ledger row back through REST.
 */
public class ThumbnailNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testThumbnailRecordsNodeResultLedger() throws Exception {
		withLoom(client -> {
			assumeVideo4j();
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");

			CortexOptions options = new CortexOptions();
			options.setMetaPath(Files.createTempDirectory("node-it-thumb"));
			ThumbnailNode node = new ThumbnailNode(client, options, new ThumbnailNodeOptions());

			NodeResult result = node.process(NodeContext.create(media(video1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			boolean recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.map(NodeResultResponse::getNodeKind)
				.anyMatch("thumbnail"::equals);
			assertThat(recorded).as("thumbnail node-result ledger row must be readable via REST").isTrue();
		});
	}
}
