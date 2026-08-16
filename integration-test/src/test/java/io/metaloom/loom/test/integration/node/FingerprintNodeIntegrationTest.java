package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.fp.FingerprintNode;
import io.metaloom.cortex.node.fp.FingerprintNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;

/**
 * Integration test for {@code FingerprintNode}. The node computes a real multi-sector video fingerprint (video4j/OpenCV) on a real video and writes the whole-asset (window 0) row
 * to {@code asset_fingerprint_comp}; the test reads the fingerprint component back through REST.
 */
public class FingerprintNodeIntegrationTest extends AbstractNodeIntegrationTest {

	@Test
	public void testFingerprintPersistsComp() throws Exception {
		withLoom(client -> {
			assumeVideo4j();
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");

			FingerprintNode node = new FingerprintNode(client, cortexOptions(), new FingerprintNodeOptions());
			NodeResult result = node.process(NodeContext.create(media(video1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			int fingerprints = client.listAssetFingerprintComps(asset.getUuid()).sync().body().getData().size();
			assertThat(fingerprints).as("fingerprint component must be readable via REST").isGreaterThanOrEqualTo(1);
		});
	}
}
