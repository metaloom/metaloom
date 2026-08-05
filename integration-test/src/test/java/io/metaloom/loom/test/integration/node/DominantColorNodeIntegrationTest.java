package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.color.DominantColorNode;
import io.metaloom.cortex.node.color.DominantColorNodeOptions;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code DominantColorNode}. Nothing about this node is stubbed - it has no
 * model and no sidecar, so it runs its real clustering against a real PNG on disk and a real
 * upstream detections payload, then persists through a real {@code LoomHttpClient}.
 *
 * <p>
 * The palette is written as an {@code asset_json_comp} row with {@code schemaType=dominant-color}
 * plus a ledger row pointing at it, and the test reads both back through REST. The German name is
 * asserted on the read-back specifically: it is the one output carrying non-ASCII characters, and
 * it has to survive Jackson, JSONB and Postgres unchanged.
 * </p>
 */
public class DominantColorNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;

	private static final String RED = "#FF0000";
	private static final String BLUE = "#0000FF";

	/** A vertical split: left 60% red, right 40% blue. PNG, so the decoded colours are exact. */
	private static byte[] splitPng() throws Exception {
		BufferedImage image = new BufferedImage(IMAGE_W, IMAGE_H, BufferedImage.TYPE_INT_RGB);
		int boundary = (int) Math.round(IMAGE_W * 0.6d);
		for (int y = 0; y < IMAGE_H; y++) {
			for (int x = 0; x < IMAGE_W; x++) {
				image.setRGB(x, y, x < boundary ? 0xFF0000 : 0x0000FF);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/**
	 * One element of the {@code detections} port, in the shape {@code FacedetectNode} emits: the
	 * box plus the convention and the dimensions it was measured against, repeated on every
	 * element because an element is what travels downstream on its own.
	 */
	private static JsonObject detection(int index, double x, double y, double w, double h) {
		return new JsonObject()
			.put("index", index)
			.put("type", "face")
			.put("label", "face")
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d)
			.put("coordinates", "ABSOLUTE_PIXELS")
			.put("imageWidth", IMAGE_W)
			.put("imageHeight", IMAGE_H);
	}

	@Test
	public void testDominantColorPersistsJsonCompAndLedger() throws Exception {
		withLoom(client -> {
			// A .png suffix, not the default .bin: FilterHelper.isImage decides on the extension
			// alone, so a .bin file would make the node skip regardless of its bytes.
			UniqueAsset unique = createUniqueAsset(client, "image/png", splitPng(), ".png");

			DominantColorNode node = new DominantColorNode(client, cortexOptions(), new DominantColorNodeOptions());

			// One box entirely inside the red band, one entirely inside the blue band. The port is
			// MANY: one element per box, not one element listing every box.
			NodeContext<LoomMedia> ctx = NodeContext.create(unique.media(),
				NodeInputs.builder()
					.inputs(DominantColorNode.IN_DETECTIONS, List.of(
						detection(0, 20, 20, 100, 100).encode(),
						detection(1, 280, 20, 100, 100).encode()))
					.build());

			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(DominantColorNode.OUT_HEX)).isEqualTo(RED);
			assertThat(result.get(DominantColorNode.OUT_TERM)).isEqualTo("red");
			assertThat(result.get(DominantColorNode.OUT_REGION_COUNT)).isEqualTo(3);

			// The component must be readable back through REST, with the palette intact.
			JsonCompResponse comp = client.listAssetJsonComps(unique.asset().getUuid()).sync().body().getData().stream()
				.filter(c -> "dominant-color".equals(c.getSchemaType()))
				.findFirst()
				.orElse(null);
			assertThat(comp).as("dominant-color json component must be readable via REST").isNotNull();
			assertThat(comp.getProducerVersion())
				.as("the algorithm version is the provenance of every emitted name")
				.isEqualTo(DominantColorNode.ALGORITHM_VERSION);

			JsonObject payload = comp.getData();
			JsonArray regions = payload.getJsonArray("regions");
			assertThat(regions).hasSize(3);

			JsonObject whole = regions.getJsonObject(0);
			assertThat(whole.getString("id")).isEqualTo("whole");
			assertThat(whole.getJsonObject("dominant").getString("hex")).isEqualTo(RED);
			assertThat(whole.getJsonObject("dominant").getDouble("share")).isCloseTo(0.6d, org.assertj.core.data.Offset.offset(0.02d));
			assertThat(whole.getJsonArray("palette")).hasSize(2);
			assertThat(whole.getJsonArray("palette").getJsonObject(1).getString("hex")).isEqualTo(BLUE);

			// This is what this test exists for: UTF-8 through Jackson, JSONB and Postgres.
			assertThat(whole.getJsonObject("dominant").getJsonObject("name").getString("de"))
				.as("the German name must survive the JSONB round trip byte for byte")
				.isEqualTo("kräftiges Rot");
			assertThat(whole.getJsonObject("dominant").getJsonObject("name").getString("en")).isEqualTo("vivid red");

			// The per-detection regions carry their own colours.
			assertThat(regions.getJsonObject(1).getString("id")).isEqualTo("face-0");
			assertThat(regions.getJsonObject(1).getJsonObject("dominant").getString("hex")).isEqualTo(RED);
			assertThat(regions.getJsonObject(2).getString("id")).isEqualTo("face-1");
			assertThat(regions.getJsonObject(2).getJsonObject("dominant").getString("hex")).isEqualTo(BLUE);
			assertThat(regions.getJsonObject(2).getJsonObject("dominant").getJsonObject("name").getString("de"))
				.isEqualTo("tiefes Blau");

			// ...and the ledger row points at the component.
			NodeResultResponse recorded = client.listAssetNodeResults(unique.asset().getUuid()).sync().body().getData().stream()
				.filter(r -> "dominant-color".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("dominant-color node-result ledger row must be readable via REST").isNotNull();
			assertThat(recorded.getState()).isEqualTo("SUCCESS");
			assertThat(recorded.getResultRef().getString("table")).isEqualTo("asset_json_comp");
			assertThat(recorded.getResultRef().getJsonArray("uuids").getList())
				.contains(comp.getUuid().toString());
		});
	}
}
