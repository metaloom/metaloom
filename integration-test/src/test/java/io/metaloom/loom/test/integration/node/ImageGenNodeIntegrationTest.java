package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.imagegen.ImageGenClient;
import io.metaloom.cortex.node.imagegen.ImageGenNode;
import io.metaloom.cortex.node.imagegen.ImageGenNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

/**
 * Integration test for {@code ImageGenNode}. The node runs its real prompt-in / image-out + persistence path against a real image asset, but its
 * injected {@link ImageGenClient} is replaced by a stub returning fixed PNG bytes instead of calling a live FastAPI sidecar. The generated PNG is
 * written to the local {@code imagegen_bin} cache and an {@code imagegen} node-result ledger row is recorded; the test reads that ledger row back
 * through REST (ledger-only persistence - the produced bytes stay local, like {@code ThumbnailNode}/{@code TtsNode}).
 */
public class ImageGenNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final byte[] FAKE_PNG = "PNG\r\n\nfake-generated-image".getBytes();

	/** An ImageGenClient that returns fixed PNG bytes instead of calling the FastAPI sidecar. */
	private static ImageGenClient stubClient() {
		return new ImageGenClient("localhost", 0, "/generate", "/remix", 0) {
			@Override
			public byte[] generate(String prompt, int width, int height, Integer seed, int steps) {
				return FAKE_PNG;
			}

			@Override
			public byte[] remix(BufferedImage source, String prompt, double strength, Integer seed, int steps) {
				return FAKE_PNG;
			}
		};
	}

	@Test
	public void testImageGenWritesImageAndRecordsLedger() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			Path metaPath = Files.createTempDirectory("node-it-imagegen");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			ImageGenNode node = new ImageGenNode(client, options, new ImageGenNodeOptions().setPrompt("a red panda astronaut"), stubClient());

			NodeResult result = node.process(NodeContext.create(media(image1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			// The PNG must have been written to the local imagegen_bin cache.
			String outPath = result.get(ImageGenNode.OUTPUT_IMAGE_PATH);
			assertThat(outPath).as("the node must emit the generated image path").isNotNull();
			assertThat(Files.exists(Path.of(outPath))).as("the PNG must be written under metaPath/imagegen_bin").isTrue();
			assertThat(Files.readAllBytes(Path.of(outPath))).isEqualTo(FAKE_PNG);

			// The imagegen node-result ledger row must be readable via REST.
			boolean recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.map(NodeResultResponse::getNodeKind)
				.anyMatch("imagegen"::equals);
			assertThat(recorded).as("imagegen node-result ledger row must be readable via REST").isTrue();
		});
	}
}
