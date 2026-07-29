package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.color.RegionResolver.Resolution;
import io.vertx.core.json.JsonObject;

public class RegionResolverTest {

	private static final int IMAGE_W = 400;

	private static final int IMAGE_H = 200;

	@Test
	public void testTheWholeImageIsMeasuredByDefault() {
		Resolution resolution = resolve(new DominantColorNodeOptions(), List.of());

		assertThat(resolution.regions()).hasSize(1);
		RegionSource whole = resolution.regions().get(0);
		assertThat(whole.id()).isEqualTo("whole");
		assertThat(whole.kind()).isEqualTo(RegionKind.IMAGE);
		assertThat(whole.box()).isEqualTo(new Box(0, 0, IMAGE_W, IMAGE_H));
	}

	@Test
	public void testANormalizedStaticRegionIsScaledToPixels() {
		DominantColorNodeOptions options = new DominantColorNodeOptions()
			.setIncludeWholeImage(false)
			.setUseDetections(false)
			.setRegionX(0.25d).setRegionY(0.25d).setRegionW(0.5d).setRegionH(0.5d);

		Resolution resolution = resolve(options, List.of());

		assertThat(resolution.regions()).hasSize(1);
		assertThat(resolution.regions().get(0).kind()).isEqualTo(RegionKind.CONFIG);
		assertThat(resolution.regions().get(0).box()).isEqualTo(new Box(100, 50, 200, 100));
	}

	@Test
	public void testAnAbsoluteStaticRegionPassesThrough() {
		DominantColorNodeOptions options = new DominantColorNodeOptions()
			.setIncludeWholeImage(false)
			.setUseDetections(false)
			.setRegionCoordinates(DominantColorNodeOptions.ABSOLUTE_PIXELS)
			.setRegionX(10).setRegionY(20).setRegionW(30).setRegionH(40);

		assertThat(resolve(options, List.of()).regions().get(0).box()).isEqualTo(new Box(10, 20, 30, 40));
	}

	@Test
	public void testAbsoluteDetectionsMeasuredAgainstTheSameImagePassThrough() {
		String element = detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			0, "face", 40, 40, 80, 80).encode();

		Resolution resolution = resolve(detectionsOnly(), List.of(element));

		assertThat(resolution.regions()).hasSize(1);
		RegionSource region = resolution.regions().get(0);
		assertThat(region.id()).isEqualTo("face-0");
		// There is no more per-source addressing - every detection-derived region shares one origin
		// tag now that the port carries them, not a named "detectionSources" list.
		assertThat(region.source()).isEqualTo("detections");
		assertThat(region.kind()).isEqualTo(RegionKind.DETECTION);
		assertThat(region.box()).isEqualTo(new Box(40, 40, 80, 80));
	}

	/**
	 * The detector measured against a half-size copy. Silently mis-cropping every box would be
	 * worse than rescaling and logging it.
	 */
	@Test
	public void testAbsoluteDetectionsMeasuredAgainstADifferentSizeAreRescaled() {
		String element = detection(IMAGE_W / 2, IMAGE_H / 2, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			0, "face", 20, 20, 40, 40).encode();

		Resolution resolution = resolve(detectionsOnly(), List.of(element));

		assertThat(resolution.regions().get(0).box()).isEqualTo(new Box(40, 40, 80, 80));
	}

	/**
	 * The shape the facedetect video path emits - it has no frame size to report.
	 */
	@Test
	public void testAbsoluteDetectionsWithoutPayloadDimensionsAreUsedAsIs() {
		String element = detection(null, null, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			0, "face", 40, 40, 80, 80).encode();

		assertThat(resolve(detectionsOnly(), List.of(element)).regions().get(0).box())
			.isEqualTo(new Box(40, 40, 80, 80));
	}

	/**
	 * Normalised coordinates are resolution-independent by definition, so they scale by the decoded
	 * image even when the producer reported no dimensions of its own.
	 */
	@Test
	public void testNormalizedDetectionsScaleByTheDecodedImageEvenWithoutPayloadDimensions() {
		String element = detection(null, null, DominantColorNodeOptions.NORMALIZED,
			0, "face", 0.1d, 0.2d, 0.2d, 0.4d).encode();

		assertThat(resolve(detectionsOnly(), List.of(element)).regions().get(0).box())
			.isEqualTo(new Box(40, 40, 80, 80));
	}

	@Test
	public void testNormalizedDetectionsIgnoreThePayloadDimensions() {
		String element = detection(9999, 9999, DominantColorNodeOptions.NORMALIZED,
			0, "face", 0.1d, 0.2d, 0.2d, 0.4d).encode();

		assertThat(resolve(detectionsOnly(), List.of(element)).regions().get(0).box())
			.isEqualTo(new Box(40, 40, 80, 80));
	}

	@Test
	public void testABoxStraddlingTheEdgeIsClampedRatherThanDropped() {
		String element = detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			0, "face", 350, 150, 200, 200).encode();

		Resolution resolution = resolve(detectionsOnly(), List.of(element));

		assertThat(resolution.regions()).hasSize(1);
		assertThat(resolution.regions().get(0).box()).isEqualTo(new Box(350, 150, 50, 50));
		assertThat(resolution.dropped()).isZero();
	}

	@Test
	public void testABoxEntirelyOutsideTheImageIsDropped() {
		String element = detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			0, "face", 900, 900, 80, 80).encode();

		Resolution resolution = resolve(detectionsOnly(), List.of(element));

		assertThat(resolution.regions()).isEmpty();
		assertThat(resolution.dropped()).isEqualTo(1);
	}

	@Test
	public void testABoxBelowTheMinimumPixelCountIsDropped() {
		List<String> elements = List.of(
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 0, "face", 10, 10, 4, 4).encode(),
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 1, "face", 40, 40, 80, 80).encode());

		Resolution resolution = resolve(detectionsOnly().setMinRegionPixels(64), elements);

		assertThat(resolution.regions()).hasSize(1);
		assertThat(resolution.regions().get(0).id()).isEqualTo("face-1");
		assertThat(resolution.dropped()).isEqualTo(1);
	}

	/**
	 * A non-zero frame index in an image pipeline can only mean a video detector was wired in.
	 */
	@Test
	public void testADetectionFromANonZeroVideoFrameIsDropped() {
		String element = detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			0, "face", 40, 40, 80, 80).put("frame", 3).encode();

		Resolution resolution = resolve(detectionsOnly(), List.of(element));

		assertThat(resolution.regions()).isEmpty();
		assertThat(resolution.dropped()).isEqualTo(1);
	}

	@Test
	public void testTheCapKeepsTheLargestBoxesAndReportsTheRemainder() {
		List<String> elements = List.of(
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 0, "face", 0, 0, 20, 20).encode(),
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 1, "face", 30, 0, 100, 100).encode(),
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 2, "face", 140, 0, 30, 30).encode(),
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 3, "face", 180, 0, 90, 90).encode(),
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 4, "face", 280, 0, 40, 40).encode());

		Resolution resolution = resolve(detectionsOnly().setMaxRegions(2), elements);

		assertThat(resolution.regions()).extracting(RegionSource::id).containsExactly("face-1", "face-3");
		assertThat(resolution.truncated()).isEqualTo(3);
	}

	@Test
	public void testTheCapNeverDropsTheWholeImageOrTheConfiguredRegion() {
		DominantColorNodeOptions options = new DominantColorNodeOptions()
			.setMaxRegions(1)
			.setRegionX(0).setRegionY(0).setRegionW(0.5d).setRegionH(0.5d);
		List<String> elements = List.of(
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 0, "face", 0, 0, 100, 100).encode(),
			detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS, 1, "face", 200, 0, 50, 50).encode());

		Resolution resolution = resolve(options, elements);

		assertThat(resolution.regions()).extracting(RegionSource::id).containsExactly("whole", "region", "face-0");
		assertThat(resolution.truncated()).isEqualTo(1);
	}

	@Test
	public void testMalformedUpstreamPayloadIsIgnoredRatherThanThrowing() {
		List<String> elements = List.of("not json at all");

		assertThatNoException().isThrownBy(() -> resolve(detectionsOnly(), elements));
		assertThat(resolve(new DominantColorNodeOptions(), elements).regions())
			.extracting(RegionSource::id).containsExactly("whole");
	}

	@Test
	public void testAnAbsentUpstreamOutputSimplyYieldsNoDetectionRegions() {
		assertThat(resolve(detectionsOnly(), List.of()).regions()).isEmpty();
		assertThat(resolve(detectionsOnly(), null).regions()).isEmpty();
	}

	@Test
	public void testDetectionProvenanceIsCarriedThrough() {
		String element = detection(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			7, "cat", 40, 40, 80, 80).put("type", "animal").put("confidence", 0.83d).encode();

		RegionSource region = resolve(detectionsOnly(), List.of(element)).regions().get(0);

		assertThat(region.id()).isEqualTo("cat-7");
		assertThat(region.label()).isEqualTo("cat");
		assertThat(region.type()).isEqualTo("animal");
		assertThat(region.confidence()).isEqualTo(0.83d);
		assertThat(region.frame()).isZero();
	}

	private static Resolution resolve(DominantColorNodeOptions options, List<String> detections) {
		return new RegionResolver(options).resolve(detections, IMAGE_W, IMAGE_H);
	}

	private static DominantColorNodeOptions detectionsOnly() {
		return new DominantColorNodeOptions().setIncludeWholeImage(false);
	}

	/**
	 * One self-contained {@code IN_DETECTIONS} element: the port is {@code MANY}, so every detection
	 * is its own element rather than an entry in a batch payload.
	 */
	private static JsonObject detection(Integer imageWidth, Integer imageHeight, String coordinates,
		int index, String label, double x, double y, double w, double h) {
		JsonObject json = new JsonObject()
			.put("index", index)
			.put("type", label)
			.put("label", label)
			.put("frame", 0)
			.put("coordinates", coordinates)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d);
		if (imageWidth != null && imageHeight != null) {
			json.put("imageWidth", imageWidth).put("imageHeight", imageHeight);
		}
		return json;
	}
}
