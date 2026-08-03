package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.imagemanip.AspectMode;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNode;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNodeOptions;
import io.metaloom.cortex.node.imagemanip.OutputFormat;
import io.metaloom.cortex.node.imagemanip.PadFill;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code ImageManipulationNode}. Nothing is stubbed: the node has no model and no sidecar, so it reframes a real PNG on disk and
 * then persists through a real {@code LoomHttpClient} against an in-process Loom and a pooled Postgres.
 *
 * <p>
 * The node is <strong>ledger-only</strong> - the reframed bytes stay in the worker's {@code imagemanip_bin} cache - so what this guards is the ledger
 * contract specifically: that an {@code asset_node_result} row reaches Postgres and comes back through REST carrying the {@code producerVersion} that
 * identifies which framing produced the artifact.
 * </p>
 */
public class ImageManipulationNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final int IMAGE_W = 200;

	private static final int IMAGE_H = 400;

	private static final Color BASE_COLOUR = new Color(20, 40, 60);

	private static byte[] png(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(BASE_COLOUR);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String detection(int x, int y, int w, int h) {
		return new JsonObject()
			.put("index", 0)
			.put("type", "face")
			.put("label", "face")
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d)
			.put("coordinates", "ABSOLUTE_PIXELS")
			.encode();
	}

	@Test
	public void testVerticalVideoFixWritesArtifactAndRecordsLedger() throws Exception {
		withLoom(client -> {
			// A .png suffix, not the default .bin: FilterHelper.isImage decides on the extension alone, so a
			// .bin file would make the node skip regardless of its bytes.
			UniqueAsset unique = createUniqueAsset(client, "image/png", png(IMAGE_W, IMAGE_H), ".png");

			ImageManipulationNodeOptions options = new ImageManipulationNodeOptions()
				.setOperations("AUTOROTATE,ASPECT")
				.setTargetAspect("16:9")
				.setAspectMode(AspectMode.PAD)
				.setPadFill(PadFill.BLUR)
				.setOutputFormat(OutputFormat.PNG);
			// The shared cortexOptions() carries no metaPath, and this node writes an artifact under it.
			CortexOptions cortexOptions = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-imagemanip"));
			ImageManipulationNode node = new ImageManipulationNode(client, cortexOptions, options);

			NodeResult result = node.process(NodeContext.create(unique.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(ImageManipulationNode.OUT_FLAG)).isEqualTo("DONE");

			String artifactPath = result.get(ImageManipulationNode.OUT_IMAGE);
			assertThat(artifactPath).as("the node must emit the reframed image path").isNotNull();
			Path artifact = Path.of(artifactPath);
			assertThat(Files.exists(artifact)).as("the reframed image should exist in the local imagemanip_bin cache").isTrue();
			assertThat(artifact.startsWith(cortexOptions.getMetaPath().resolve("imagemanip_bin")))
				.as("the artifact must live under metaPath/imagemanip_bin but was " + artifact)
				.isTrue();

			// A portrait source padded to 16:9: the height is kept and the margins are blurred picture,
			// never black bars.
			BufferedImage reframed = ImageIO.read(artifact.toFile());
			assertThat(reframed.getHeight()).as("padding must not crop the picture").isEqualTo(IMAGE_H);
			assertThat((double) reframed.getWidth() / reframed.getHeight()).isCloseTo(16d / 9d, org.assertj.core.data.Offset.offset(0.02d));
			assertThat(reframed.getRGB(2, IMAGE_H / 2) | 0xFF000000)
				.as("the margin should be a blurred enlargement of the picture, not a black bar")
				.isNotEqualTo(Color.BLACK.getRGB());

			// The original asset file must be byte-identical: this node reframes a copy, it never edits the archive.
			assertThat(Files.readAllBytes(Path.of(unique.media().absolutePath())))
				.as("the source media must never be modified")
				.isEqualTo(png(IMAGE_W, IMAGE_H));

			// The geometry port is the node's own account of what it did, and it crosses the wire as JSON.
			JsonObject geometry = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY));
			assertThat(geometry.getInteger("sourceWidth")).isEqualTo(IMAGE_W);
			assertThat(geometry.getInteger("resultHeight")).isEqualTo(IMAGE_H);

			// The ledger row is the only durable trace, so it has to survive the REST round trip.
			NodeResultResponse recorded = client.listAssetNodeResults(unique.asset().getUuid()).sync().body().getData().stream()
				.filter(r -> "image-manipulation".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("image-manipulation node-result ledger row must be readable via REST").isNotNull();
			assertThat(recorded.getState()).isEqualTo("SUCCESS");
			assertThat(recorded.getProducerVersion())
				.as("the producer version records which framing produced the artifact, not merely that some manipulation node ran")
				.startsWith(ImageManipulationNode.ALGORITHM_VERSION + ":");
			assertThat(recorded.getResultRef())
				.as("the node is ledger-only - the bytes stay local, so there is no component to point at")
				.isNull();
		});
	}

	@Test
	public void testSubjectCropFramesUpstreamDetections() throws Exception {
		withLoom(client -> {
			UniqueAsset unique = createUniqueAsset(client, "image/png", png(400, 400), ".png");

			CortexOptions cortexOptions = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-imagemanip"));
			ImageManipulationNode node = new ImageManipulationNode(client, cortexOptions,
				new ImageManipulationNodeOptions()
					.setOperations("SUBJECT_CROP")
					.setSubjectPadding(0.2d)
					.setOutputFormat(OutputFormat.PNG));

			// The shape a real graph produces: facedetect's MANY detections port feeding this node's.
			NodeContext<LoomMedia> ctx = NodeContext.create(unique.media(),
				NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS, List.of(detection(300, 300, 40, 40))).build());

			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			JsonObject geometry = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY));
			assertThat(geometry.getInteger("subjects")).isEqualTo(1);
			JsonObject crop = geometry.getJsonArray("operations").getJsonObject(0);
			assertThat(crop.getString("op")).isEqualTo("SUBJECT_CROP");
			assertThat(crop.getInteger("x")).as("the crop should follow the subject into the bottom-right quadrant").isGreaterThanOrEqualTo(200);
			assertThat(crop.getInteger("y")).isGreaterThanOrEqualTo(200);
		});
	}

	@Test
	public void testFailureIsRecordedInTheLedgerRatherThanSilentlyDropped() throws Exception {
		withLoom(client -> {
			// Bytes that are not an image, behind an extension that says they are - so the node accepts the
			// item and then fails to decode it.
			UniqueAsset unique = createUniqueAsset(client, "image/png", "this is not a PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8),
				".png");

			CortexOptions cortexOptions = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-imagemanip"));
			ImageManipulationNode node = new ImageManipulationNode(client, cortexOptions,
				new ImageManipulationNodeOptions().setOperations("RESIZE").setMaxLongEdge(100));

			// Visibly FAILED both to the pipeline and in the ledger - NodeContextImpl.next() would have
			// reported this as SUCCESS with no artifact, which is why the node aborts instead.
			NodeResult result = node.process(NodeContext.create(unique.media()));
			assertThat(result.getState()).isEqualTo(ResultState.FAILED);
			assertThat(result.get(ImageManipulationNode.OUT_FLAG)).isEqualTo("FAILED");

			NodeResultResponse recorded = client.listAssetNodeResults(unique.asset().getUuid()).sync().body().getData().stream()
				.filter(r -> "image-manipulation".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("a failed run must still leave a ledger row").isNotNull();
			assertThat(recorded.getState()).isEqualTo("FAILED");
			assertThat(recorded.getResultRef()).isNull();
		});
	}
}
