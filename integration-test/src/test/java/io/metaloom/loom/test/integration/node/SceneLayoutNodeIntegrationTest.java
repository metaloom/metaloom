package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNode;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code SceneLayoutNode}. Nothing about this node is stubbed - it has no
 * model and no sidecar, so it runs its real geometry against a real 16-bit depth PNG on disk and a
 * real upstream detections payload, then persists through a real {@code LoomHttpClient}.
 *
 * <p>
 * The layout is written as an {@code asset_json_comp} row with {@code schemaType=scene-layout} plus
 * a ledger row pointing at it, and the test reads both back through REST.
 * </p>
 */
public class SceneLayoutNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String DEPTH_MODEL = "depth-anything/Depth-Anything-V2-Small-hf";

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;
	private static final int MAP_W = 200;
	private static final int MAP_H = 100;

	/** Left half near (0.9), right half far (0.1). The map is half the image size in both axes, so
	 * the node has to project the boxes before sampling. */
	private static File writeDepthMap(Path dir) throws Exception {
		BufferedImage map = new BufferedImage(MAP_W, MAP_H, BufferedImage.TYPE_USHORT_GRAY);
		WritableRaster raster = map.getRaster();
		for (int y = 0; y < MAP_H; y++) {
			for (int x = 0; x < MAP_W; x++) {
				raster.setSample(x, y, 0, x < MAP_W / 2 ? (int) (0.9 * 65535) : (int) (0.1 * 65535));
			}
		}
		File file = dir.resolve("depth.png").toFile();
		ImageIO.write(map, "png", file);
		return file;
	}

	private static JsonObject detection(int index, double x, double y, double w, double h) {
		return new JsonObject()
			.put("index", index)
			.put("type", "face")
			.put("label", "face")
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d);
	}

	@Test
	public void testSceneLayoutPersistsJsonCompAndLedger() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			Path metaPath = Files.createTempDirectory("node-it-scene-layout");
			File mapFile = writeDepthMap(metaPath);

			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			SceneLayoutNode node = new SceneLayoutNode(client, options, new SceneLayoutNodeOptions());

			// One face in the near half of the image, one in the far half.
			JsonObject depthMeta = new JsonObject()
				.put("model", DEPTH_MODEL)
				.put("convention", "NEARNESS")
				.put("source", "RELATIVE")
				.put("width", MAP_W).put("height", MAP_H)
				.put("imageWidth", IMAGE_W).put("imageHeight", IMAGE_H)
				.put("path", mapFile.getAbsolutePath());

			JsonObject detections = new JsonObject()
				.put("imageWidth", IMAGE_W)
				.put("imageHeight", IMAGE_H)
				.put("coordinates", "ABSOLUTE_PIXELS")
				.put("detections", new JsonArray()
					.add(detection(0, 40, 40, 80, 80))
					.add(detection(1, 280, 40, 80, 80)));

			NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(media(image1()), Map.of(
				"depthmap", Map.of(
					"depthmap_path", mapFile.getAbsolutePath(),
					"depthmap_meta", depthMeta.encode()),
				"facedetect", Map.of("detections", detections.encode())));

			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(SceneLayoutNode.OUTPUT_SCENE_LAYOUT_OBJECTS)).isEqualTo(2);

			// The component must be readable back through REST, with the layout intact.
			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> "scene-layout".equals(c.getSchemaType()))
				.findFirst()
				.orElse(null);
			assertThat(comp).as("scene-layout json component must be readable via REST").isNotNull();
			assertThat(comp.getProducerVersion())
				.as("the depth model behind the layout is its provenance")
				.isEqualTo(DEPTH_MODEL);

			JsonObject payload = comp.getData();
			JsonArray objects = payload.getJsonArray("objects");
			assertThat(objects).hasSize(2);

			// The near face is foreground, the far one background - banding survives the round trip.
			assertThat(objects.getJsonObject(0).getJsonObject("depth").getString("band")).isEqualTo("FOREGROUND");
			assertThat(objects.getJsonObject(1).getJsonObject("depth").getString("band")).isEqualTo("BACKGROUND");

			List<String> predicates = payload.getJsonArray("relations").stream()
				.map(JsonObject.class::cast)
				.map(r -> r.getString("subject") + " " + r.getString("predicate") + " " + r.getString("object"))
				.toList();
			assertThat(predicates).contains("face-0 IN_FRONT_OF face-1", "face-1 BEHIND face-0");

			assertThat(payload.getJsonArray("phrases").getList())
				.contains("face-0 is in front of face-1");

			// ...and the ledger row points at the component.
			NodeResultResponse recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.filter(r -> "scene-layout".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("scene-layout node-result ledger row must be readable via REST").isNotNull();
		});
	}
}
