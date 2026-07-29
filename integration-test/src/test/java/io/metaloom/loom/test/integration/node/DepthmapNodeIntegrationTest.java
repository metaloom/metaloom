package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.depthmap.DepthMode;
import io.metaloom.cortex.node.depthmap.DepthmapClient;
import io.metaloom.cortex.node.depthmap.DepthmapNode;
import io.metaloom.cortex.node.depthmap.DepthmapNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code DepthmapNode}. The node runs its real image-in / map-out and
 * persistence path against a real image asset and a real {@code LoomHttpClient}, but its injected
 * {@link DepthmapClient} is replaced by a stub returning a fixed 16-bit PNG instead of calling a
 * live FastAPI sidecar.
 *
 * <p>
 * The produced map is written to the local {@code depthmap_bin} cache and a {@code depthmap}
 * node-result ledger row is recorded; the test reads that ledger row back through REST
 * (ledger-only persistence - the bytes stay local, like {@code ThumbnailNode}/{@code TtsNode}/
 * {@code ImageGenNode}).
 * </p>
 */
public class DepthmapNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String MODEL = "depth-anything/Depth-Anything-V2-Small-hf";
	private static final int MAP_W = 64;
	private static final int MAP_H = 48;

	/** A genuine 16-bit grayscale PNG: left half near, right half far. */
	private static byte[] gradientPng() throws Exception {
		BufferedImage map = new BufferedImage(MAP_W, MAP_H, BufferedImage.TYPE_USHORT_GRAY);
		WritableRaster raster = map.getRaster();
		for (int y = 0; y < MAP_H; y++) {
			for (int x = 0; x < MAP_W; x++) {
				raster.setSample(x, y, 0, x < MAP_W / 2 ? (int) (0.9 * 65535) : (int) (0.1 * 65535));
			}
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(map, "png", bos);
		return bos.toByteArray();
	}

	/** A DepthmapClient that returns a fixed map instead of calling the FastAPI sidecar. */
	private static DepthmapClient stubClient(byte[] png) {
		return new DepthmapClient("localhost", 0, 1000) {
			@Override
			public JsonObject depth(BufferedImage image, DepthMode mode, String modelOverride, int maxDim) {
				return new JsonObject()
					.put("model", MODEL)
					.put("convention", "NEARNESS")
					.put("source", "RELATIVE")
					.put("width", MAP_W)
					.put("height", MAP_H)
					.put("png_b64", Base64.getEncoder().encodeToString(png))
					.put("stats", new JsonObject().put("p05", 0.1d).put("p50", 0.5d).put("p95", 0.9d));
			}
		};
	}

	@Test
	public void testDepthmapWritesMapAndRecordsLedger() throws Exception {
		byte[] png = gradientPng();

		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			Path metaPath = Files.createTempDirectory("node-it-depthmap");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			DepthmapNode node = new DepthmapNode(client, options, new DepthmapNodeOptions(), stubClient(png));

			NodeResult result = node.process(NodeContext.create(media(image1())));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			// The map must have been written to the local depthmap_bin cache, byte for byte.
			String outPath = result.get(DepthmapNode.OUT_MAP);
			assertThat(outPath).as("the node must emit the depth map path").isNotNull();
			assertThat(outPath).contains("depthmap_bin");
			assertThat(Files.exists(Path.of(outPath))).as("the PNG must be written under metaPath/depthmap_bin").isTrue();
			assertThat(Files.readAllBytes(Path.of(outPath))).isEqualTo(png);

			// ...and it must still be readable as the 16-bit map every consumer expects.
			BufferedImage written = ImageIO.read(new File(outPath));
			assertThat(written.getType()).isEqualTo(BufferedImage.TYPE_USHORT_GRAY);
			assertThat(written.getRaster().getSample(2, 2, 0))
				.as("the near half must encode brighter than the far half")
				.isGreaterThan(written.getRaster().getSample(MAP_W - 2, 2, 0));

			// The metadata carries what a downstream node needs to interpret the artifact.
			JsonObject meta = new JsonObject(result.get(DepthmapNode.OUT_META));
			assertThat(meta.getString("model")).isEqualTo(MODEL);
			assertThat(meta.getString("convention")).isEqualTo("NEARNESS");
			assertThat(meta.getInteger("width")).isEqualTo(MAP_W);
			assertThat(meta.getInteger("imageWidth")).as("the source image size must travel too").isPositive();

			// The depthmap node-result ledger row must be readable via REST, stamped with the model.
			NodeResultResponse recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.filter(r -> "depthmap".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("depthmap node-result ledger row must be readable via REST").isNotNull();
			assertThat(recorded.getProducerVersion())
				.as("the ledger must record which depth model produced the map")
				.isEqualTo(MODEL);
		});
	}
}
