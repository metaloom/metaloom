package io.metaloom.cortex.node.imagemanip;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * The node end to end, offline ({@code LoomClient == null}).
 *
 * <p>
 * The fixtures are flat quadrants, so "did the right thing happen to the picture" is a pixel read rather than a size check.
 * </p>
 */
class ImageManipulationNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;

	@BeforeEach
	void setup() {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
	}

	private ImageManipulationNode node(ImageManipulationNodeOptions options) {
		return new ImageManipulationNode(null, cortexOptions, options);
	}

	private static ImageManipulationNodeOptions options() {
		return new ImageManipulationNodeOptions().setOutputFormat(OutputFormat.PNG);
	}

	private StubLoomMedia media(File file) {
		StubLoomMedia media = new StubLoomMedia(file.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
		return media;
	}

	private StubLoomMedia pngMedia(int width, int height) throws Exception {
		return media(ImageManipFixtures.writePng(tempDir, "asset.png", ImageManipFixtures.quadrants(width, height)));
	}

	private static BufferedImage artifactOf(NodeResult result) throws Exception {
		String path = result.get(ImageManipulationNode.OUT_IMAGE);
		assertNotNull(path, "the node emitted no artifact path");
		BufferedImage image = ImageIO.read(new File(path));
		assertNotNull(image, "the artifact at " + path + " could not be decoded");
		return image;
	}

	private static Color at(BufferedImage image, double relX, double relY) {
		return new Color(image.getRGB(
			Math.min(image.getWidth() - 1, (int) (image.getWidth() * relX)),
			Math.min(image.getHeight() - 1, (int) (image.getHeight() * relY))), true);
	}

	// ── the basics ───────────────────────────────────────────────────────

	@Test
	void testWritesTheResultIntoTheLocalArtifactCache() throws Exception {
		NodeResult result = node(options().setOperations("RESIZE").setMaxLongEdge(40))
			.process(NodeContext.create(pngMedia(80, 40)));

		assertThat(result).isSuccess();
		assertEquals("DONE", result.get(ImageManipulationNode.OUT_FLAG));

		Path artifact = Path.of(result.get(ImageManipulationNode.OUT_IMAGE));
		assertTrue(Files.exists(artifact), "no artifact was written");
		assertTrue(artifact.startsWith(tempDir.toPath().resolve("imagemanip_bin")),
			"the artifact should live under metaPath/imagemanip_bin, got " + artifact);
		assertEquals(40, artifactOf(result).getWidth());
	}

	@Test
	void testTheSourceFileIsNeverModified() throws Exception {
		File source = ImageManipFixtures.writePng(tempDir, "asset.png", ImageManipFixtures.quadrants(80, 40));
		byte[] before = Files.readAllBytes(source.toPath());

		node(options().setOperations("CROP").setCropWidth(0.5d)).process(NodeContext.create(media(source)));

		assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(source.toPath())), "the node rewrote the source file");
	}

	@Test
	void testNonImageMediaSelfSkips() {
		StubLoomMedia video = new StubLoomMedia(new File(tempDir, "clip.mp4").getAbsolutePath(), true, false, false, false);
		try {
			Files.writeString(Path.of(video.absolutePath()), "not really a video");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		NodeResult result = node(options().setOperations("RESIZE").setMaxLongEdge(40)).process(NodeContext.create(video));
		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	void testAnUndecodableImageFailsRatherThanReportingSuccess() throws Exception {
		// 🔴 ctx.failure(...).next() would report SUCCESS with no artifact. Failure must abort.
		File broken = new File(tempDir, "broken.png");
		Files.writeString(broken.toPath(), "this is not a PNG");

		NodeResult result = node(options().setOperations("RESIZE").setMaxLongEdge(40)).process(NodeContext.create(media(broken)));
		assertEquals(ResultState.FAILED, result.getState(), "an undecodable image must fail, not succeed");
	}

	// ── operations ───────────────────────────────────────────────────────

	@Test
	void testAutorotateStraightensAJpegThatDeclaresAQuarterTurn() throws Exception {
		File file = ImageManipFixtures.writeJpegWithOrientation(tempDir, "sideways.jpg", ImageManipFixtures.quadrants(80, 40), 6);
		NodeResult result = node(options().setOperations("AUTOROTATE")).process(NodeContext.create(media(file)));

		assertThat(result).isSuccess();
		BufferedImage image = artifactOf(result);
		assertEquals(40, image.getWidth(), "a quarter turn must swap the axes");
		assertEquals(80, image.getHeight());
	}

	@Test
	void testAutorotateIsANoOpForAnImageWithNoExif() throws Exception {
		NodeResult result = node(options().setOperations("AUTOROTATE")).process(NodeContext.create(pngMedia(80, 40)));
		assertThat(result).isSuccess();
		assertEquals(80, artifactOf(result).getWidth());
	}

	@Test
	void testCropTakesTheRequestedQuadrant() throws Exception {
		NodeResult result = node(options().setOperations("CROP").setCropWidth(0.5d).setCropHeight(0.5d))
			.process(NodeContext.create(pngMedia(80, 40)));

		BufferedImage image = artifactOf(result);
		assertEquals(40, image.getWidth());
		assertEquals(20, image.getHeight());
		assertEquals(ImageManipFixtures.TOP_LEFT.getRGB(), at(image, 0.5d, 0.5d).getRGB());
	}

	@Test
	void testAspectCropCutsTheLongAxis() throws Exception {
		NodeResult result = node(options().setOperations("ASPECT").setTargetAspect("1:1").setAspectMode(AspectMode.CROP))
			.process(NodeContext.create(pngMedia(80, 40)));

		BufferedImage image = artifactOf(result);
		assertEquals(image.getWidth(), image.getHeight(), "the result should be square");
		assertEquals(40, image.getHeight(), "padding, not cropping, happened");
	}

	@Test
	void testTheVerticalVideoPresetPadsAPortraitFrameWithABlurredBackdrop() throws Exception {
		// The headline recipe: AUTOROTATE + ASPECT(PAD, BLUR) at 16:9.
		StubLoomMedia media = media(ImageManipFixtures.writePng(tempDir, "portrait.png", ImageManipFixtures.quadrants(40, 80)));
		NodeResult result = node(options()
			.setOperations("AUTOROTATE,ASPECT")
			.setTargetAspect("16:9")
			.setAspectMode(AspectMode.PAD)
			.setPadFill(PadFill.BLUR))
				.process(NodeContext.create(media));

		assertThat(result).isSuccess();
		BufferedImage image = artifactOf(result);
		assertEquals(16d / 9d, (double) image.getWidth() / image.getHeight(), 0.02d);
		assertEquals(80, image.getHeight(), "padding must not crop the picture");

		Color margin = at(image, 0.02d, 0.5d);
		assertNotEquals(Color.BLACK.getRGB(), margin.getRGB(), "the margin is a black bar, not a blurred backdrop");
		assertEquals(255, margin.getAlpha());
	}

	@Test
	void testColourPaddingDrawsTheConfiguredBars() throws Exception {
		StubLoomMedia media = media(ImageManipFixtures.writePng(tempDir, "portrait.png", ImageManipFixtures.quadrants(40, 80)));
		NodeResult result = node(options()
			.setOperations("ASPECT")
			.setTargetAspect("16:9")
			.setAspectMode(AspectMode.PAD)
			.setPadFill(PadFill.COLOR)
			.setPadColor("#000000"))
				.process(NodeContext.create(media));

		assertEquals(Color.BLACK.getRGB(), at(artifactOf(result), 0.02d, 0.5d).getRGB());
	}

	@Test
	void testResizeBoundsTheLongEdgeWithoutUpscaling() throws Exception {
		NodeResult big = node(options().setOperations("RESIZE").setMaxLongEdge(40)).process(NodeContext.create(pngMedia(80, 40)));
		assertEquals(40, artifactOf(big).getWidth());

		NodeResult small = node(options().setOperations("RESIZE").setMaxLongEdge(400)).process(NodeContext.create(pngMedia(80, 40)));
		assertEquals(80, artifactOf(small).getWidth(), "an image smaller than the bound must not be enlarged");
	}

	@Test
	void testTheChainRunsInTheOrderGiven() throws Exception {
		// Crop to the top-left quadrant, then bound the result - the sizes only work out in this order.
		NodeResult result = node(options()
			.setOperations("CROP,RESIZE")
			.setCropWidth(0.5d).setCropHeight(0.5d)
			.setMaxLongEdge(20))
				.process(NodeContext.create(pngMedia(80, 40)));

		BufferedImage image = artifactOf(result);
		assertEquals(20, image.getWidth());
		assertEquals(10, image.getHeight());
		assertEquals(ImageManipFixtures.TOP_LEFT.getRGB(), at(image, 0.5d, 0.5d).getRGB());
	}

	// ── subject crop ─────────────────────────────────────────────────────

	@Test
	void testSubjectCropFramesTheDetectionsItWasGiven() throws Exception {
		// One subject in the bottom-right quadrant: the crop must contain it and not the top-left one.
		NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(pngMedia(80, 80),
			NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS, List.of(ImageManipFixtures.detection(60, 60, 10, 10))).build());

		NodeResult result = node(options().setOperations("SUBJECT_CROP").setSubjectPadding(0.2d)).process(ctx);

		assertThat(result).isSuccess();
		JsonObject geometry = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY));
		JsonObject step = geometry.getJsonArray("operations").getJsonObject(0);
		assertEquals("SUBJECT_CROP", step.getString("op"));
		assertTrue(step.getInteger("x") >= 40, "the crop should sit in the right half, got x=" + step.getInteger("x"));
		assertTrue(step.getInteger("y") >= 40, "the crop should sit in the bottom half, got y=" + step.getInteger("y"));
		assertEquals(1, geometry.getInteger("subjects"));
	}

	@Test
	void testSubjectCropUnionsEveryDetectionRatherThanFramingOne() throws Exception {
		NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(pngMedia(80, 80),
			NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS,
				List.of(ImageManipFixtures.detection(5, 5, 10, 10), ImageManipFixtures.detection(65, 65, 10, 10))).build());

		NodeResult result = node(options().setOperations("SUBJECT_CROP").setSubjectPadding(0d)).process(ctx);

		JsonObject step = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY)).getJsonArray("operations").getJsonObject(0);
		// Both subjects must survive: the window has to span from the first to the second.
		assertTrue(step.getInteger("width") >= 70, "a group of two was cropped to one, width=" + step.getInteger("width"));
	}

	@Test
	void testSubjectCropCentresWhenNothingWasDetected() throws Exception {
		NodeResult result = node(options().setOperations("SUBJECT_CROP").setTargetAspect("1:1").setSubjectFallback(SubjectFallback.CENTRE))
			.process(NodeContext.create(pngMedia(80, 40)));

		assertThat(result).isSuccess();
		BufferedImage image = artifactOf(result);
		assertEquals(image.getWidth(), image.getHeight(), "the centre fallback should have produced the square window");
	}

	@Test
	void testSubjectCropCanSkipOrFailWhenNothingWasDetected() throws Exception {
		NodeResult skipped = node(options().setOperations("SUBJECT_CROP").setSubjectFallback(SubjectFallback.SKIP))
			.process(NodeContext.create(pngMedia(80, 40)));
		assertEquals(ResultState.SKIPPED, skipped.getState());
		assertEquals("SKIPPED", skipped.get(ImageManipulationNode.OUT_FLAG));

		NodeResult failed = node(options().setOperations("SUBJECT_CROP").setSubjectFallback(SubjectFallback.FAIL))
			.process(NodeContext.create(pngMedia(80, 40)));
		assertEquals(ResultState.FAILED, failed.getState());
	}

	@Test
	void testDetectionBoxesAreRotatedWithThePixels() throws Exception {
		// 🔴 The headline correctness property. facedetect decodes with ImageIO, which ignores EXIF, so
		// its box is in stored space. After AUTOROTATE the crop must still land on the same subject.
		//
		// Source is 80 wide x 40 high with orientation 6 (quarter turn clockwise), so the result is
		// 40x80. A box at stored (60,5) - the top-right region - must end up near the bottom-right of
		// the rotated frame, i.e. large y. Untransformed it would stay at y=5 and frame the wrong place.
		File file = ImageManipFixtures.writeJpegWithOrientation(tempDir, "sideways.jpg", ImageManipFixtures.quadrants(80, 40), 6);
		NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(media(file),
			NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS, List.of(ImageManipFixtures.detection(60, 5, 10, 10))).build());

		NodeResult result = node(options().setOperations("AUTOROTATE,SUBJECT_CROP").setSubjectPadding(0d)).process(ctx);
		assertThat(result).isSuccess();

		JsonObject geometry = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY));
		JsonObject crop = geometry.getJsonArray("operations").getJsonObject(1);
		assertEquals("SUBJECT_CROP", crop.getString("op"));
		assertTrue(crop.getInteger("y") >= 50,
			"the box was not carried through the rotation - the crop is at y=" + crop.getInteger("y") + " in an 80-tall frame");
	}

	@Test
	void testACropRebasesTheBoxesForALaterSubjectCrop() throws Exception {
		// A CROP moves the frame origin; a SUBJECT_CROP after it must measure against the cut window.
		NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(pngMedia(80, 80),
			NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS, List.of(ImageManipFixtures.detection(50, 50, 10, 10))).build());

		NodeResult result = node(options()
			.setOperations("CROP,SUBJECT_CROP")
			.setCropX(0.5d).setCropY(0.5d).setCropWidth(0.5d).setCropHeight(0.5d)
			.setSubjectPadding(0d))
				.process(ctx);

		assertThat(result).isSuccess();
		JsonObject crop = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY)).getJsonArray("operations").getJsonObject(1);
		// In the 40x40 cut window the subject sits at (10,10); an un-rebased box would ask for (50,50),
		// which the clamp would drag to the far corner.
		assertTrue(crop.getInteger("x") <= 15 && crop.getInteger("y") <= 15,
			"the box was not rebased onto the cropped frame, crop is at " + crop.getInteger("x") + "," + crop.getInteger("y"));
	}

	// ── caching ──────────────────────────────────────────────────────────

	@Test
	void testASecondRunIsServedFromTheLocalCache() throws Exception {
		ImageManipulationNode node = node(options().setOperations("RESIZE").setMaxLongEdge(40));
		StubLoomMedia media = pngMedia(80, 40);

		NodeResult first = node.process(NodeContext.create(media));
		Path artifact = Path.of(first.get(ImageManipulationNode.OUT_IMAGE));

		// Overwrite the artifact with a sentinel: a genuine recompute would restore the real image.
		Files.writeString(artifact, "sentinel");
		NodeResult second = node.process(NodeContext.create(media));

		assertThat(second).isSuccess();
		assertEquals(artifact.toString(), second.get(ImageManipulationNode.OUT_IMAGE));
		assertEquals("sentinel", Files.readString(artifact), "the node recomputed instead of serving the cache");
	}

	@Test
	void testACacheHitOnADeletedArtifactRecomputes() throws Exception {
		ImageManipulationNode node = node(options().setOperations("RESIZE").setMaxLongEdge(40));
		StubLoomMedia media = pngMedia(80, 40);

		NodeResult first = node.process(NodeContext.create(media));
		Path artifact = Path.of(first.get(ImageManipulationNode.OUT_IMAGE));
		Files.delete(artifact);

		NodeResult second = node.process(NodeContext.create(media));
		assertThat(second).isSuccess();
		assertTrue(Files.exists(artifact), "a deleted artifact should have been rebuilt, not handed on as a dead path");
	}

	@Test
	void testTwoDifferentlyConfiguredNodesDoNotShareAnArtifact() throws Exception {
		// The options digest is in the file name, not only in the cache key: a 16:9 hero crop and a 1:1
		// thumbnail key on the same media hash.
		StubLoomMedia media = pngMedia(80, 40);
		NodeResult wide = node(options().setOperations("ASPECT").setTargetAspect("16:9")).process(NodeContext.create(media));
		NodeResult square = node(options().setOperations("ASPECT").setTargetAspect("1:1")).process(NodeContext.create(media));

		assertNotEquals(wide.get(ImageManipulationNode.OUT_IMAGE), square.get(ImageManipulationNode.OUT_IMAGE),
			"both configurations wrote to the same path and would serve each other's output");
		assertNotEquals(artifactOf(wide).getWidth(), artifactOf(square).getWidth());
	}

	@Test
	void testBetterDetectionsAreNotServedTheFirstRunsCrop() throws Exception {
		// 🔴 The detections are a second input that changes the pixels, so they belong in the digest.
		ImageManipulationNode node = node(options().setOperations("SUBJECT_CROP").setSubjectPadding(0d));
		StubLoomMedia media = pngMedia(80, 80);

		NodeResult first = node.process(NodeContext.create(media,
			NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS, List.of(ImageManipFixtures.detection(5, 5, 10, 10))).build()));
		NodeResult second = node.process(NodeContext.create(media,
			NodeInputs.builder().inputs(ImageManipulationNode.IN_DETECTIONS, List.of(ImageManipFixtures.detection(60, 60, 10, 10))).build()));

		assertNotEquals(first.get(ImageManipulationNode.OUT_IMAGE), second.get(ImageManipulationNode.OUT_IMAGE),
			"the second run was served the first run's crop from the cache");
	}

	// ── reporting ────────────────────────────────────────────────────────

	@Test
	void testTheGeometryPortReportsWhatWasActuallyDone() throws Exception {
		NodeResult result = node(options()
			.setOperations("AUTOROTATE,ASPECT,RESIZE")
			.setTargetAspect("1:1")
			.setMaxLongEdge(30))
				.process(NodeContext.create(pngMedia(80, 40)));

		JsonObject geometry = new JsonObject(result.get(ImageManipulationNode.OUT_GEOMETRY));
		assertEquals(80, geometry.getInteger("sourceWidth"));
		assertEquals(40, geometry.getInteger("sourceHeight"));
		assertEquals(30, geometry.getInteger("resultWidth"));
		assertEquals(30, geometry.getInteger("resultHeight"));
		assertEquals("PNG", geometry.getString("format"));
		assertEquals(3, geometry.getJsonArray("operations").size(), "every applied step should be reported");
	}

	@Test
	void testTheArtifactExtensionFollowsTheOutputFormat() throws Exception {
		NodeResult png = node(options().setOperations("RESIZE").setMaxLongEdge(40)).process(NodeContext.create(pngMedia(80, 40)));
		assertTrue(png.get(ImageManipulationNode.OUT_IMAGE).endsWith(".png"));

		NodeResult jpeg = node(new ImageManipulationNodeOptions().setOutputFormat(OutputFormat.JPEG)
			.setOperations("RESIZE").setMaxLongEdge(40)).process(NodeContext.create(pngMedia(80, 40)));
		assertTrue(jpeg.get(ImageManipulationNode.OUT_IMAGE).endsWith(".jpg"));
	}
}
