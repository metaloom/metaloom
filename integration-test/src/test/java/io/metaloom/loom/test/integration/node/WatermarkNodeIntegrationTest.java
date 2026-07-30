package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.watermark.WatermarkNode;
import io.metaloom.cortex.node.watermark.WatermarkNodeOptions;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

/**
 * Integration test for {@code WatermarkNode}. Nothing about the image path is stubbed - it has no model and no sidecar, so it composites a real overlay
 * onto a real PNG on disk and then persists through a real {@code LoomHttpClient}.
 *
 * <p>
 * The node is <strong>ledger-only</strong>: the marked bytes stay in the worker's local {@code watermark_bin} cache, so there is no component table to read
 * back. What this test guards is therefore the ledger contract specifically - that an {@code asset_node_result} row reaches Postgres and comes back through
 * REST with the {@code producerVersion} identifying <em>which</em> watermark was burned in, which is the only durable record that the asset was marked.
 * </p>
 */
public class WatermarkNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;

	private static final Color BASE_COLOUR = new Color(20, 40, 60);
	private static final Color MARK_COLOUR = new Color(240, 10, 120);

	private static byte[] png(int width, int height, Color colour, int type) throws Exception {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(colour);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String markBase64() throws Exception {
		return Base64.getEncoder().encodeToString(png(40, 40, MARK_COLOUR, BufferedImage.TYPE_INT_ARGB));
	}

	@Test
	public void testWatermarkWritesArtifactAndRecordsLedger() throws Exception {
		withLoom(client -> {
			// A .png suffix, not the default .bin: FilterHelper.isImage decides on the extension alone, so a .bin file would make the node skip regardless
			// of its bytes.
			UniqueAsset unique = createUniqueAsset(client, "image/png", png(IMAGE_W, IMAGE_H, BASE_COLOUR, BufferedImage.TYPE_INT_RGB), ".png");

			WatermarkNodeOptions options = new WatermarkNodeOptions()
				.setWatermarkBase64(markBase64())
				.setRelX(0.0)
				.setRelY(0.0)
				.setScale(0.25);
			// The shared cortexOptions() carries no metaPath, and this node writes an artifact under it.
			CortexOptions cortexOptions = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-watermark"));
			WatermarkNode node = new WatermarkNode(client, cortexOptions, options);

			NodeContext<LoomMedia> ctx = NodeContext.create(unique.media());
			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(result.get(WatermarkNode.OUT_FLAG)).isEqualTo("DONE");
			assertThat(result.get(WatermarkNode.OUT_VIDEO)).as("the video port must stay empty for an image asset").isNull();

			// The artifact is a real, readable image with the mark where the options asked for it and the source dimensions unchanged.
			String artifactPath = result.get(WatermarkNode.OUT_IMAGE);
			assertThat(artifactPath).as("the node must emit the marked image path").isNotNull();
			Path artifact = Path.of(artifactPath);
			assertThat(Files.exists(artifact)).as("the marked PNG should exist in the local watermark_bin cache").isTrue();
			assertThat(artifact.startsWith(cortexOptions.getMetaPath().resolve("watermark_bin")))
				.as("the artifact must live under metaPath/watermark_bin but was " + artifact)
				.isTrue();

			BufferedImage marked = ImageIO.read(artifact.toFile());
			assertThat(marked.getWidth()).isEqualTo(IMAGE_W);
			assertThat(marked.getHeight()).isEqualTo(IMAGE_H);
			assertThat(marked.getRGB(5, 5) | 0xFF000000)
				.as("relX/relY 0.0 should place the mark in the top-left corner")
				.isEqualTo(MARK_COLOUR.getRGB());
			assertThat(marked.getRGB(IMAGE_W - 5, IMAGE_H - 5) | 0xFF000000)
				.as("the opposite corner should still be untouched base pixels")
				.isEqualTo(BASE_COLOUR.getRGB());

			// The original asset file must be byte-identical: this node marks a copy, it does not edit the archive.
			assertThat(Files.readAllBytes(Path.of(unique.media().absolutePath())))
				.as("the source media must never be modified")
				.isEqualTo(png(IMAGE_W, IMAGE_H, BASE_COLOUR, BufferedImage.TYPE_INT_RGB));

			// The ledger row is the only durable trace, so it has to survive the REST round trip.
			NodeResultResponse recorded = client.listAssetNodeResults(unique.asset().getUuid()).sync().body().getData().stream()
				.filter(r -> "watermark".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("watermark node-result ledger row must be readable via REST").isNotNull();
			assertThat(recorded.getState()).isEqualTo("SUCCESS");
			assertThat(recorded.getProducerVersion())
				.as("the producer version records which watermark was burned in, not merely that some watermark node ran")
				.startsWith(WatermarkNode.ALGORITHM_VERSION + ":");
			assertThat(recorded.getResultRef())
				.as("the node is ledger-only - the bytes stay local, so there is no component to point at")
				.isNull();
		});
	}

	@Test
	public void testFailureIsRecordedInTheLedgerRatherThanSilentlyDropped() throws Exception {
		withLoom(client -> {
			UniqueAsset unique = createUniqueAsset(client, "image/png", png(IMAGE_W, IMAGE_H, BASE_COLOUR, BufferedImage.TYPE_INT_RGB), ".png");

			// A misconfigured watermark. The run must be visibly FAILED both to the pipeline and in the ledger - NodeContextImpl.next() would have reported
			// it as SUCCESS with no artifact, which is why the node aborts instead.
			CortexOptions cortexOptions = new CortexOptions().setMetaPath(Files.createTempDirectory("node-it-watermark"));
			WatermarkNode node = new WatermarkNode(client, cortexOptions,
				new WatermarkNodeOptions().setWatermarkBase64("!!! not base64 !!!"));

			NodeResult result = node.process(NodeContext.create(unique.media()));
			assertThat(result.getState()).isEqualTo(ResultState.FAILED);
			assertThat(result.get(WatermarkNode.OUT_FLAG)).isEqualTo("FAILED");

			NodeResultResponse recorded = client.listAssetNodeResults(unique.asset().getUuid()).sync().body().getData().stream()
				.filter(r -> "watermark".equals(r.getNodeKind()))
				.findFirst()
				.orElse(null);
			assertThat(recorded).as("a failed watermark run must still be recorded").isNotNull();
			assertThat(recorded.getState()).isEqualTo("FAILED");
		});
	}
}
