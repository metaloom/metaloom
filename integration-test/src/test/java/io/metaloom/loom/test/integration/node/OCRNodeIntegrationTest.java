package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.ocr.OCRNode;
import io.metaloom.cortex.node.ocr.OCRNodeOptions;
import io.metaloom.cortex.node.ocr.OCRProvider;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;

/**
 * Integration test for {@code OCRNode}. A deterministic {@link OCRProvider} stub stands in for the Tesseract native runtime (the node's compute is not under
 * test here), while the file, the {@link io.metaloom.loom.client.http.LoomHttpClient} and the Loom backend are all real: the recognized text must reach the
 * {@code ocr} JSON component and be readable back through REST.
 */
public class OCRNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String RECOGNIZED = "integration test recognized text";

	private OCRProvider stubProvider() {
		return new OCRProvider() {
			@Override
			public String name() {
				return "stub-ocr";
			}

			@Override
			public String recognizeText(File imageFile, String language) {
				return RECOGNIZED;
			}
		};
	}

	@Test
	public void testOCRPersistsJsonComp() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			OCRNode node = new OCRNode(client, cortexOptions(), new OCRNodeOptions(), stubProvider());
			NodeResult result = node.process(NodeContext.create(media(image1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> "ocr".equals(c.getSchemaType()))
				.findFirst().orElse(null);
			assertThat(comp).as("ocr JSON component must be readable via REST").isNotNull();
			assertThat(comp.getData().getString("text")).isEqualTo(RECOGNIZED);
		});
	}
}
