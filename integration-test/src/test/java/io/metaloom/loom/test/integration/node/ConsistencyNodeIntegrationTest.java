package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.consistency.ConsistencyNode;
import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * Integration test for {@code ConsistencyNode}. The node counts zero-chunks of a real media file and writes the consistency block onto the asset row; the
 * test reads it back through the REST API.
 */
public class ConsistencyNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testConsistencyPersistsToAssetRow() throws Exception {
		withLoom(client -> {
			// A real video file (unique copy) so the node runs its byte-level zero-chunk scan against a collision-free asset.
			UniqueAsset ua = createUniqueMediaAsset(client, video1(), "video/mp4", "consistency");

			ConsistencyNode node = new ConsistencyNode(client, cortexOptions(), new ConsistencyNodeOptions());
			NodeResult result = node.process(NodeContext.create(ua.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(ua.sha512()).sync().body();
			assertThat(reloaded.getConsistency()).as("consistency block must be readable via REST").isNotNull();
			assertThat(reloaded.getConsistency().getZeroChunkCount())
				.as("zero-chunk count must be persisted")
				.isNotNull()
				.isGreaterThanOrEqualTo(0L);
		});
	}
}
