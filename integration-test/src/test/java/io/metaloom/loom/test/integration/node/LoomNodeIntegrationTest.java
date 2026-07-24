package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.loom.LoomNode;
import io.metaloom.cortex.node.loom.LoomNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;

/**
 * Integration test for {@code LoomNode}. The sink node collects upstream hash outputs and bulk-updates the asset row in Loom; the test seeds the upstream
 * {@code md5sum} output, flushes, and reads the persisted MD5 back through REST.
 */
public class LoomNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testLoomNodeBulkUpdatesAssetHashes() throws Exception {
		withLoom(client -> {
			UniqueAsset ua = createUniqueAsset(client, "application/octet-stream", "loom node payload".getBytes(StandardCharsets.UTF_8));
			String md5 = HashUtils.computeMD5(ua.file()).toString();

			LoomNode node = new LoomNode(client, cortexOptions(), new LoomNodeOptions());
			// Seed the upstream md5 output the way the pipeline would (LoomNode reads upstreamOutput("md5sum", "md5")).
			NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(ua.media(),
				Map.of("md5sum", Map.of("md5", md5)));
			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			node.flush();

			AssetResponse reloaded = client.loadAsset(ua.sha512()).sync().body();
			assertThat(reloaded.getHashes().getMD5())
				.as("MD5 bulk-synced by LoomNode must be readable via REST")
				.isEqualTo(HashUtils.computeMD5(ua.file()));
		});
	}
}
