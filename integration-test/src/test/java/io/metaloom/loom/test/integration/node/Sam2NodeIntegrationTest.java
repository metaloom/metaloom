package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.sam2.Sam2Box;
import io.metaloom.cortex.node.sam2.Sam2Client;
import io.metaloom.cortex.node.sam2.Sam2Mode;
import io.metaloom.cortex.node.sam2.Sam2Node;
import io.metaloom.cortex.node.sam2.Sam2NodeOptions;
import io.metaloom.cortex.node.sam2.video.Sam2FrameSampler;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code Sam2Node}. The node runs its real image-in / masks-out and persistence
 * path against a real image asset and a real {@code LoomHttpClient}, but its injected
 * {@link Sam2Client} is replaced by a stub returning fixed binary mask PNGs instead of calling a live
 * FastAPI sidecar.
 *
 * <p>
 * Two things are checked that a unit test cannot: that the ledger row survives a real REST round trip
 * stamped with the checkpoint, and that <strong>nothing</strong> lands in {@code detection}. The
 * second is the point — "ledger only" was a decision, and the only way to know a node did not quietly
 * start writing rows is to read the table back through the API that would serve them.
 * </p>
 */
public class Sam2NodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String MODEL = "facebook/sam2.1-hiera-small";
	private static final int MASK_W = 64;
	private static final int MASK_H = 48;

	/** A genuine 8-bit grayscale PNG: 255 inside a rectangle, 0 outside. */
	private static byte[] maskPng(int x, int y, int w, int h) throws Exception {
		BufferedImage mask = new BufferedImage(MASK_W, MASK_H, BufferedImage.TYPE_BYTE_GRAY);
		WritableRaster raster = mask.getRaster();
		for (int py = 0; py < MASK_H; py++) {
			for (int px = 0; px < MASK_W; px++) {
				boolean inside = px >= x && px < x + w && py >= y && py < y + h;
				raster.setSample(px, py, 0, inside ? 255 : 0);
			}
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(mask, "png", bos);
		return bos.toByteArray();
	}

	/** A Sam2Client that returns fixed masks instead of calling the FastAPI sidecar. */
	private static Sam2Client stubClient(byte[] first, byte[] second) {
		return new Sam2Client("localhost", 0, 1000) {
			@Override
			public JsonObject segment(String imageB64, Sam2Mode mode, List<Sam2Box> boxes, Sam2NodeOptions options) {
				return new JsonObject()
					.put("model", MODEL)
					.put("mode", (mode == null ? Sam2Mode.AUTOMATIC : mode).name())
					.put("width", MASK_W)
					.put("height", MASK_H)
					.put("masks", new JsonArray()
						.add(mask(0, first, 4, 4, 20, 20, 0.93d))
						.add(mask(1, second, 34, 4, 20, 20, 0.81d)))
					.put("truncated", new JsonObject().put("masks", 0));
			}

			private JsonObject mask(int index, byte[] png, int x, int y, int w, int h, double score) {
				return new JsonObject()
					.put("index", index)
					.put("png_b64", Base64.getEncoder().encodeToString(png))
					.put("area", w * h)
					.put("score", score)
					.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h));
			}
		};
	}

	@Test
	public void testSam2WritesMasksAndRecordsLedgerOnly() throws Exception {
		byte[] first = maskPng(4, 4, 20, 20);
		byte[] second = maskPng(34, 4, 20, 20);

		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, image1(), "image/jpeg");

			Path metaPath = Files.createTempDirectory("node-it-sam2");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			Sam2Node node = new Sam2Node(client, options, new Sam2NodeOptions(),
				stubClient(first, second), new Sam2FrameSampler());

			// Previews on, because the overlay is only composited for a run that asked for one - and
			// the overlay is the artifact the docs page shows.
			NodeResult result = node.process(NodeContext.create(media(image1()),
				new NodeInputs(java.util.Map.of(), java.util.Set.of(), null, null, true)));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			// Every mask must have been written to the local sam2_bin cache, byte for byte.
			List<String> paths = result.elements(Sam2Node.OUT_MASKS);
			assertThat(paths).as("the node must emit one element per mask").hasSize(2);
			assertThat(paths.get(0)).contains("sam2_bin");
			assertThat(Files.readAllBytes(Path.of(paths.get(0)))).isEqualTo(first);
			assertThat(Files.readAllBytes(Path.of(paths.get(1)))).isEqualTo(second);

			// ...and must still be readable as the binary mask every consumer expects.
			BufferedImage written = ImageIO.read(new File(paths.get(0)));
			assertThat(written.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
			assertThat(written.getRaster().getSample(10, 10, 0)).isEqualTo(255);
			assertThat(written.getRaster().getSample(60, 40, 0)).isZero();

			// The overlay is the whole-result picture; the masks port previews only its first element.
			String overlay = result.get(Sam2Node.OUT_OVERLAY);
			assertThat(overlay).as("the overlay must be written when previews are on").isNotNull();
			assertThat(Files.exists(Path.of(overlay))).isTrue();

			// The manifest carries what a consumer needs to interpret the artifacts.
			JsonObject manifest = new JsonObject(result.get(Sam2Node.OUT_SEGMENTS));
			assertThat(manifest.getString("model")).isEqualTo(MODEL);
			assertThat(manifest.getInteger("width")).isEqualTo(MASK_W);
			assertThat(manifest.getInteger("imageWidth")).as("the source image size must travel too").isPositive();
			assertThat(manifest.getJsonArray("masks")).hasSize(2);
			assertThat(Files.exists(Path.of(manifest.getString("dir")).resolve("manifest.json")))
				.as("the manifest commits the artifact directory and must be on disk")
				.isTrue();

			// The sam2 ledger row must be readable via REST, stamped with the checkpoint.
			NodeResultResponse recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.filter(r -> "sam2".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("sam2 node-result ledger row must be readable via REST").isNotNull();
			assertThat(recorded.getProducerVersion())
				.as("the ledger must record which checkpoint drew these edges")
				.isEqualTo("sam2/1:" + MODEL);
			assertThat(recorded.getResultRef())
				.as("ledger-only: the mask bytes stay local, so there is nothing to point at")
				.isNull();

			// Ledger only. detection has no column for polygonal geometry, so this node deliberately
			// writes none - and the way to know that is still true is to read the table back.
			// getData() is null rather than empty when the asset has no detections at all, which is
			// itself the strongest form of the assertion.
			List<DetectionResponse> detections = client.listAssetDetections(asset.getUuid()).sync().body().getData();
			if (detections != null) {
				assertThat(detections)
					.as("sam2 must not write detection rows")
					.noneMatch(d -> "sam2".equals(d.getType()) || "segmentation".equals(d.getType()));
			}
		});
	}
}
