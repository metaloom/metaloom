package io.metaloom.cortex.node.color;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * End-to-end unit test for {@link DominantColorNode} against real PNGs on disk. No Loom client, so
 * the node runs offline and every assertion is about what it computed rather than what it wrote.
 *
 * <p>
 * Every fixture is a flat-colour PNG, so the expected hex is exact rather than approximate.
 * </p>
 */
class DominantColorNodeTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final int IMAGE_W = 400;
	private static final int IMAGE_H = 200;

	private static final String STEEL = "#3B6EA5";
	private static final String RED = "#FF0000";
	private static final String BLUE = "#0000FF";
	private static final String GREEN = "#00FF00";
	private static final String YELLOW = "#FFFF00";

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;

	@BeforeEach
	void setup() {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
	}

	@Test
	void testASolidImageYieldsExactlyItsOwnColour() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));

		NodeResult result = node().process(media, NodeInputs.empty());

		assertThat(result).isSuccess();
		assertEquals(STEEL, result.get(DominantColorNode.OUT_HEX));
		assertEquals("blue", result.get(DominantColorNode.OUT_TERM));
		assertEquals("muted blue", result.get(DominantColorNode.OUT_NAME_EN));
		assertEquals("gedämpftes Blau", result.get(DominantColorNode.OUT_NAME_DE));
		assertEquals(1L, result.get(DominantColorNode.OUT_REGION_COUNT));

		JsonObject region = regions(result).getJsonObject(0);
		assertEquals("whole", region.getString("id"));
		assertEquals("IMAGE", region.getString("kind"));
		assertEquals(1, region.getJsonArray("palette").size());
		assertEquals(1.0d, region.getJsonObject("dominant").getDouble("share"), 1e-9);
		assertTrue(region.getBoolean("converged"));
	}

	@Test
	void testAllReportedRepresentationsDescribeTheSameColour() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));

		NodeResult result = node().process(media, NodeInputs.empty());
		JsonObject dominant = regions(result).getJsonObject(0).getJsonObject("dominant");

		assertEquals(STEEL, dominant.getString("hex"));
		assertEquals(0x3B, (int) dominant.getJsonObject("rgb").getInteger("r"));
		assertEquals(0x6E, (int) dominant.getJsonObject("rgb").getInteger("g"));
		assertEquals(0xA5, (int) dominant.getJsonObject("rgb").getInteger("b"));

		Lab expected = ColorSpaces.rgbToLab(Rgb.ofHex(STEEL));
		assertEquals(expected.l(), dominant.getJsonObject("lab").getDouble("l"), 0.01d);
		assertEquals(expected.a(), dominant.getJsonObject("lab").getDouble("a"), 0.01d);
		assertEquals(expected.b(), dominant.getJsonObject("lab").getDouble("b"), 0.01d);
		assertEquals(expected.chroma(), dominant.getJsonObject("lch").getDouble("c"), 0.01d);
		assertEquals(expected.hue(), dominant.getJsonObject("lch").getDouble("h"), 0.1d);

		Hsl hsl = ColorSpaces.rgbToHsl(Rgb.ofHex(STEEL));
		assertEquals(hsl.h(), dominant.getJsonObject("hsl").getDouble("h"), 0.1d);
		assertEquals(hsl.s(), dominant.getJsonObject("hsl").getDouble("s"), 0.1d);
		assertEquals(hsl.l(), dominant.getJsonObject("hsl").getDouble("l"), 0.1d);
	}

	@Test
	void testAGreyImageReportsNoHue() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "grey.png", "#808080", IMAGE_W, IMAGE_H));

		JsonObject dominant = regions(node().process(media, NodeInputs.empty())).getJsonObject(0).getJsonObject("dominant");

		assertEquals("grey", dominant.getJsonObject("name").getString("term"));
		assertEquals("Grau", dominant.getJsonObject("name").getString("de"));
		assertEquals("ACHROMATIC", dominant.getJsonObject("name").getString("chroma"));
		assertNotNull(dominant.getJsonObject("lch"));
		org.junit.jupiter.api.Assertions.assertNull(dominant.getJsonObject("lch").getDouble("h"));
	}

	@Test
	void testASplitImageRanksBothColoursByShare() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSplit(tempDir, "split.png", RED, BLUE, 0.6d, IMAGE_W, IMAGE_H));

		NodeResult result = node().process(media, NodeInputs.empty());

		assertThat(result).isSuccess();
		JsonArray palette = regions(result).getJsonObject(0).getJsonArray("palette");
		assertEquals(2, palette.size());
		assertEquals(RED, palette.getJsonObject(0).getString("hex"));
		assertEquals(0.6d, palette.getJsonObject(0).getDouble("share"), 0.01d);
		assertEquals(BLUE, palette.getJsonObject(1).getString("hex"));
		assertEquals(0.4d, palette.getJsonObject(1).getDouble("share"), 0.01d);
		assertEquals(RED, result.get(DominantColorNode.OUT_HEX));
	}

	@Test
	void testFourQuadrantsYieldFourEqualColours() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeQuadrants(tempDir, "quad.png",
			new String[] { RED, GREEN, BLUE, YELLOW }, IMAGE_W, IMAGE_H));

		NodeResult result = node(options().setClusterCount(4)).process(media, NodeInputs.empty());

		assertThat(result).isSuccess();
		JsonArray palette = regions(result).getJsonObject(0).getJsonArray("palette");
		assertEquals(4, palette.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(0.25d, palette.getJsonObject(i).getDouble("share"), 0.01d);
		}
		assertThat(hexes(palette)).containsExactlyInAnyOrder(RED, GREEN, BLUE, YELLOW);
	}

	/**
	 * The transparent half carries blue in its RGB channels. Anything that flattens alpha onto a
	 * background - or worse, interpolates over it - reports a blend instead of pure red.
	 */
	@Test
	void testFullyTransparentPixelsAreSkippedRatherThanFlattened() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeHalfTransparent(tempDir, "alpha.png", RED, BLUE, IMAGE_W, IMAGE_H));

		NodeResult result = node().process(media, NodeInputs.empty());

		assertThat(result).isSuccess();
		JsonObject region = regions(result).getJsonObject(0);
		assertEquals(RED, region.getJsonObject("dominant").getString("hex"));
		assertEquals(1.0d, region.getJsonObject("dominant").getDouble("share"), 1e-9);
		assertEquals(1, region.getJsonArray("palette").size());
	}

	@Test
	void testAFullyTransparentImageIsSkipped() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeFullyTransparent(tempDir, "ghost.png", RED, IMAGE_W, IMAGE_H));

		assertThat(node().process(media, NodeInputs.empty())).isSkipped();
	}

	@Test
	void testEachUpstreamDetectionGetsItsOwnColour() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSplit(tempDir, "split.png", RED, BLUE, 0.5d, IMAGE_W, IMAGE_H));
		List<String> detections = List.of(
			DominantColorFixtures.detection(0, "face", 20, 20, 100, 100).encode(),
			DominantColorFixtures.detection(1, "face", 260, 20, 100, 100).encode());

		NodeResult result = node().process(media, inputs(detections));

		assertThat(result).isSuccess();
		assertEquals(3L, result.get(DominantColorNode.OUT_REGION_COUNT));

		JsonArray regions = regions(result);
		assertEquals("whole", regions.getJsonObject(0).getString("id"));
		assertEquals("face-0", regions.getJsonObject(1).getString("id"));
		assertEquals("detections", regions.getJsonObject(1).getString("source"));
		assertEquals("DETECTION", regions.getJsonObject(1).getString("kind"));
		assertEquals(RED, regions.getJsonObject(1).getJsonObject("dominant").getString("hex"));
		assertEquals("face-1", regions.getJsonObject(2).getString("id"));
		assertEquals(BLUE, regions.getJsonObject(2).getJsonObject("dominant").getString("hex"));
	}

	@Test
	void testAConfiguredRegionCanBeMeasuredOnItsOwn() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSplit(tempDir, "split.png", RED, BLUE, 0.5d, IMAGE_W, IMAGE_H));
		DominantColorNodeOptions options = options()
			.setIncludeWholeImage(false)
			.setUseDetections(false)
			.setRegionX(0.6d).setRegionY(0d).setRegionW(0.4d).setRegionH(1.0d);

		NodeResult result = node(options).process(media, NodeInputs.empty());

		assertThat(result).isSuccess();
		JsonArray regions = regions(result);
		assertEquals(1, regions.size());
		assertEquals("region", regions.getJsonObject(0).getString("id"));
		assertEquals("CONFIG", regions.getJsonObject(0).getString("kind"));
		assertEquals(BLUE, regions.getJsonObject(0).getJsonObject("dominant").getString("hex"));
		assertEquals(BLUE, result.get(DominantColorNode.OUT_HEX));
	}

	@Test
	void testTheEmittedPayloadCarriesItsOwnSamplingParameters() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));

		JsonObject payload = payload(node().process(media, NodeInputs.empty()));

		assertEquals(IMAGE_W, (int) payload.getJsonObject("image").getInteger("width"));
		assertEquals(IMAGE_H, (int) payload.getJsonObject("image").getInteger("height"));
		assertEquals(40_000, (int) payload.getJsonObject("sampling").getInteger("maxSamples"));
		assertEquals(5, (int) payload.getJsonObject("sampling").getInteger("clusterCount"));
		assertEquals(42L, payload.getJsonObject("sampling").getLong("seed"));
		assertEquals(0, (int) payload.getJsonObject("truncated").getInteger("regions"));
		assertEquals(0, (int) payload.getJsonObject("truncated").getInteger("dropped"));
	}

	@Test
	void testTheResultIsReproducibleAcrossFreshNodeInstances() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeQuadrants(tempDir, "quad.png",
			new String[] { RED, GREEN, BLUE, YELLOW }, IMAGE_W, IMAGE_H));

		String first = node().process(media, NodeInputs.empty()).get(DominantColorNode.OUT_RESULT);
		String second = node().process(media, NodeInputs.empty()).get(DominantColorNode.OUT_RESULT);

		assertEquals(first, second);
	}

	/**
	 * The file's pixels are replaced between the two runs while its path stays the same, so a
	 * second run that still reports the original colour can only have come from the cache.
	 * {@code ResultOrigin} cannot be asserted directly - it is set on the context and
	 * {@link NodeResult} has no field for it to travel in. Deleting the file would not work
	 * either: {@code AbstractMediaNode} checks {@code exists()} before {@code compute()} ever runs.
	 */
	@Test
	void testASecondRunIsServedFromTheLocalCache() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));
		DominantColorNode node = node();

		NodeResult first = node.process(media, NodeInputs.empty());
		assertThat(first).isSuccess();
		assertEquals(STEEL, first.get(DominantColorNode.OUT_HEX));

		DominantColorFixtures.writeSolid(tempDir, "solid.png", RED, IMAGE_W, IMAGE_H);
		NodeResult second = node.process(media, NodeInputs.empty());

		assertThat(second).isSuccess();
		assertEquals(STEEL, second.get(DominantColorNode.OUT_HEX));
		assertEquals(first.get(DominantColorNode.OUT_RESULT), second.get(DominantColorNode.OUT_RESULT));
	}

	/**
	 * Keying the cache on the media path alone - which is what the scene-layout node does - would
	 * hand back the first detector's answer here.
	 */
	@Test
	void testChangedUpstreamDetectionsMissTheCache() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSplit(tempDir, "split.png", RED, BLUE, 0.5d, IMAGE_W, IMAGE_H));
		DominantColorNode node = node();

		List<String> one = List.of(DominantColorFixtures.detection(0, "face", 20, 20, 100, 100).encode());
		List<String> two = List.of(
			DominantColorFixtures.detection(0, "face", 20, 20, 100, 100).encode(),
			DominantColorFixtures.detection(1, "face", 260, 20, 100, 100).encode());

		NodeResult first = node.process(media, inputs(one));
		NodeResult second = node.process(media, inputs(two));

		assertEquals(2L, first.get(DominantColorNode.OUT_REGION_COUNT));
		assertEquals(3L, second.get(DominantColorNode.OUT_REGION_COUNT));
	}

	@Test
	void testANonImageIsSkipped() throws Exception {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "not an image");
		StubLoomMedia video = new StubLoomMedia(media.file().getAbsolutePath(), true, false, false, false);
		video.setSHA512(HASH);

		assertThat(node().process(video, NodeInputs.empty())).isSkipped();
	}

	@Test
	void testADisabledNodeIsSkipped() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));
		DominantColorNodeOptions options = options();
		options.setEnabled(false);

		assertThat(node(options).process(media, NodeInputs.empty())).isSkipped();
	}

	/**
	 * A file that claims to be an image and cannot be decoded is a real problem, unlike a photo
	 * with no faces in it - so it must fail rather than skip.
	 */
	@Test
	void testAnUndecodableImageFails() throws Exception {
		File file = new File(tempDir, "broken.png");
		Files.write(file.toPath(), "not a png at all".getBytes());

		assertThat(node().process(image(file), NodeInputs.empty())).isFailed();
	}

	@Test
	void testMalformedUpstreamPayloadDoesNotThrow() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));
		NodeInputs inputs = inputs(List.of("not json at all"));

		assertThatNoException().isThrownBy(() -> node().process(media, inputs));
		assertThat(node().process(media, inputs)).isSuccess();
	}

	@Test
	void testEmitPaletteFalseKeepsOnlyTheDominantColour() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeQuadrants(tempDir, "quad.png",
			new String[] { RED, GREEN, BLUE, YELLOW }, IMAGE_W, IMAGE_H));

		NodeResult result = node(options().setClusterCount(4).setEmitPalette(false)).process(media, NodeInputs.empty());

		JsonObject region = regions(result).getJsonObject(0);
		assertEquals(1, region.getJsonArray("palette").size());
		assertEquals(0.25d, region.getJsonObject("dominant").getDouble("share"), 0.01d);
	}

	@Test
	void testDroppedDetectionsAreReportedRatherThanSilentlyOmitted() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeSolid(tempDir, "solid.png", STEEL, IMAGE_W, IMAGE_H));
		List<String> detections = List.of(
			DominantColorFixtures.detection(0, "face", 900, 900, 80, 80).encode(),
			DominantColorFixtures.detection(1, "face", 20, 20, 100, 100).encode());

		JsonObject payload = payload(node().process(media, inputs(detections)));

		assertEquals(2, payload.getJsonArray("regions").size());
		assertEquals(1, (int) payload.getJsonObject("truncated").getInteger("dropped"));
	}

	@Test
	void testTheSharesOfAPaletteSumToOne() throws Exception {
		LoomMedia media = image(DominantColorFixtures.writeQuadrants(tempDir, "quad.png",
			new String[] { RED, GREEN, BLUE, YELLOW }, IMAGE_W, IMAGE_H));

		JsonArray palette = regions(node(options().setClusterCount(4)).process(media, NodeInputs.empty()))
			.getJsonObject(0).getJsonArray("palette");

		double sum = 0;
		for (int i = 0; i < palette.size(); i++) {
			sum += palette.getJsonObject(i).getDouble("share");
		}
		org.assertj.core.api.Assertions.assertThat(sum).isCloseTo(1.0d, within(0.001d));
	}

	private DominantColorNode node() {
		return node(options());
	}

	private DominantColorNode node(DominantColorNodeOptions options) {
		return new DominantColorNode(null, cortexOptions, options);
	}

	private static DominantColorNodeOptions options() {
		return new DominantColorNodeOptions();
	}

	private LoomMedia image(File file) {
		StubLoomMedia media = new StubLoomMedia(file.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
		return media;
	}

	private static NodeInputs inputs(List<String> detections) {
		return NodeInputs.builder()
			.inputs(DominantColorNode.IN_DETECTIONS, detections)
			.build();
	}

	private static JsonObject payload(NodeResult result) {
		return new JsonObject(result.get(DominantColorNode.OUT_RESULT));
	}

	private static JsonArray regions(NodeResult result) {
		return payload(result).getJsonArray("regions");
	}

	private static List<String> hexes(JsonArray palette) {
		return palette.stream().map(o -> ((JsonObject) o).getString("hex")).toList();
	}
}
