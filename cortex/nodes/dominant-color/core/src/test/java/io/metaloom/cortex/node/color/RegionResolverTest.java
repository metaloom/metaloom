package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.color.RegionResolver.Resolution;
import io.vertx.core.json.JsonObject;

public class RegionResolverTest {

	private static final int IMAGE_W = 400;

	private static final int IMAGE_H = 200;

	@Test
	public void testTheWholeImageIsMeasuredByDefault() {
		Resolution resolution = resolve(new DominantColorNodeOptions(), Map.of());

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

		Resolution resolution = resolve(options, Map.of());

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

		assertThat(resolve(options, Map.of()).regions().get(0).box()).isEqualTo(new Box(10, 20, 30, 40));
	}

	@Test
	public void testAbsoluteDetectionsMeasuredAgainstTheSameImagePassThrough() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 40, 40, 80, 80));

		Resolution resolution = resolve(detectionsOnly(), upstream("facedetect", payload));

		assertThat(resolution.regions()).hasSize(1);
		RegionSource region = resolution.regions().get(0);
		assertThat(region.id()).isEqualTo("face-0");
		assertThat(region.source()).isEqualTo("facedetect");
		assertThat(region.kind()).isEqualTo(RegionKind.DETECTION);
		assertThat(region.box()).isEqualTo(new Box(40, 40, 80, 80));
	}

	/**
	 * The detector measured against a half-size copy. Silently mis-cropping every box would be
	 * worse than rescaling and logging it.
	 */
	@Test
	public void testAbsoluteDetectionsMeasuredAgainstADifferentSizeAreRescaled() {
		JsonObject payload = detections(IMAGE_W / 2, IMAGE_H / 2, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 20, 20, 40, 40));

		Resolution resolution = resolve(detectionsOnly(), upstream("facedetect", payload));

		assertThat(resolution.regions().get(0).box()).isEqualTo(new Box(40, 40, 80, 80));
	}

	/**
	 * The shape the facedetect video path emits - it has no frame size to report.
	 */
	@Test
	public void testAbsoluteDetectionsWithoutPayloadDimensionsAreUsedAsIs() {
		JsonObject payload = detections(null, null, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 40, 40, 80, 80));

		assertThat(resolve(detectionsOnly(), upstream("facedetect", payload)).regions().get(0).box())
			.isEqualTo(new Box(40, 40, 80, 80));
	}

	/**
	 * Normalised coordinates are resolution-independent by definition, so they scale by the decoded
	 * image even when the producer reported no dimensions of its own.
	 */
	@Test
	public void testNormalizedDetectionsScaleByTheDecodedImageEvenWithoutPayloadDimensions() {
		JsonObject payload = detections(null, null, DominantColorNodeOptions.NORMALIZED,
			detection(0, "face", 0.1d, 0.2d, 0.2d, 0.4d));

		assertThat(resolve(detectionsOnly(), upstream("facedetect", payload)).regions().get(0).box())
			.isEqualTo(new Box(40, 40, 80, 80));
	}

	@Test
	public void testNormalizedDetectionsIgnoreThePayloadDimensions() {
		JsonObject payload = detections(9999, 9999, DominantColorNodeOptions.NORMALIZED,
			detection(0, "face", 0.1d, 0.2d, 0.2d, 0.4d));

		assertThat(resolve(detectionsOnly(), upstream("facedetect", payload)).regions().get(0).box())
			.isEqualTo(new Box(40, 40, 80, 80));
	}

	@Test
	public void testABoxStraddlingTheEdgeIsClampedRatherThanDropped() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 350, 150, 200, 200));

		Resolution resolution = resolve(detectionsOnly(), upstream("facedetect", payload));

		assertThat(resolution.regions()).hasSize(1);
		assertThat(resolution.regions().get(0).box()).isEqualTo(new Box(350, 150, 50, 50));
		assertThat(resolution.dropped()).isZero();
	}

	@Test
	public void testABoxEntirelyOutsideTheImageIsDropped() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 900, 900, 80, 80));

		Resolution resolution = resolve(detectionsOnly(), upstream("facedetect", payload));

		assertThat(resolution.regions()).isEmpty();
		assertThat(resolution.dropped()).isEqualTo(1);
	}

	@Test
	public void testABoxBelowTheMinimumPixelCountIsDropped() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 10, 10, 4, 4),
			detection(1, "face", 40, 40, 80, 80));

		Resolution resolution = resolve(detectionsOnly().setMinRegionPixels(64), upstream("facedetect", payload));

		assertThat(resolution.regions()).hasSize(1);
		assertThat(resolution.regions().get(0).id()).isEqualTo("face-1");
		assertThat(resolution.dropped()).isEqualTo(1);
	}

	/**
	 * A non-zero frame index in an image pipeline can only mean a video detector was wired in.
	 */
	@Test
	public void testADetectionFromANonZeroVideoFrameIsDropped() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 40, 40, 80, 80).put("frame", 3));

		Resolution resolution = resolve(detectionsOnly(), upstream("facedetect", payload));

		assertThat(resolution.regions()).isEmpty();
		assertThat(resolution.dropped()).isEqualTo(1);
	}

	@Test
	public void testTheCapKeepsTheLargestBoxesAndReportsTheRemainder() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 0, 0, 20, 20),
			detection(1, "face", 30, 0, 100, 100),
			detection(2, "face", 140, 0, 30, 30),
			detection(3, "face", 180, 0, 90, 90),
			detection(4, "face", 280, 0, 40, 40));

		Resolution resolution = resolve(detectionsOnly().setMaxRegions(2), upstream("facedetect", payload));

		assertThat(resolution.regions()).extracting(RegionSource::id).containsExactly("face-1", "face-3");
		assertThat(resolution.truncated()).isEqualTo(3);
	}

	@Test
	public void testTheCapNeverDropsTheWholeImageOrTheConfiguredRegion() {
		DominantColorNodeOptions options = new DominantColorNodeOptions()
			.setMaxRegions(1)
			.setRegionX(0).setRegionY(0).setRegionW(0.5d).setRegionH(0.5d);
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 0, 0, 100, 100),
			detection(1, "face", 200, 0, 50, 50));

		Resolution resolution = resolve(options, upstream("facedetect", payload));

		assertThat(resolution.regions()).extracting(RegionSource::id).containsExactly("whole", "region", "face-0");
		assertThat(resolution.truncated()).isEqualTo(1);
	}

	@Test
	public void testTwoDetectionSourcesProduceNonCollidingIds() {
		DominantColorNodeOptions options = detectionsOnly().setDetectionSources(List.of("facedetect", "objectdetect"));
		JsonObject faces = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 0, 0, 80, 80));
		JsonObject objects = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(0, "face", 100, 0, 80, 80));

		Resolution resolution = resolve(options, Map.of(
			"facedetect", Map.of("detections", faces.encode()),
			"objectdetect", Map.of("detections", objects.encode())));

		assertThat(resolution.regions()).extracting(RegionSource::id)
			.containsExactly("facedetect:face-0", "objectdetect:face-0");
	}

	@Test
	public void testMalformedUpstreamPayloadIsIgnoredRatherThanThrowing() {
		Map<String, Map<String, Object>> upstream = Map.of("facedetect", Map.of("detections", "not json at all"));

		assertThatNoException().isThrownBy(() -> resolve(detectionsOnly(), upstream));
		assertThat(resolve(new DominantColorNodeOptions(), upstream).regions())
			.extracting(RegionSource::id).containsExactly("whole");
	}

	@Test
	public void testAnAbsentUpstreamOutputSimplyYieldsNoDetectionRegions() {
		assertThat(resolve(detectionsOnly(), Map.of()).regions()).isEmpty();
		assertThat(resolve(detectionsOnly(), null).regions()).isEmpty();
	}

	@Test
	public void testDetectionProvenanceIsCarriedThrough() {
		JsonObject payload = detections(IMAGE_W, IMAGE_H, DominantColorNodeOptions.ABSOLUTE_PIXELS,
			detection(7, "cat", 40, 40, 80, 80).put("type", "animal").put("confidence", 0.83d));

		RegionSource region = resolve(detectionsOnly(), upstream("facedetect", payload)).regions().get(0);

		assertThat(region.id()).isEqualTo("cat-7");
		assertThat(region.label()).isEqualTo("cat");
		assertThat(region.type()).isEqualTo("animal");
		assertThat(region.confidence()).isEqualTo(0.83d);
		assertThat(region.frame()).isZero();
	}

	private static Resolution resolve(DominantColorNodeOptions options, Map<String, Map<String, Object>> upstream) {
		return new RegionResolver(options).resolve(upstream, IMAGE_W, IMAGE_H);
	}

	private static DominantColorNodeOptions detectionsOnly() {
		return new DominantColorNodeOptions().setIncludeWholeImage(false);
	}

	private static Map<String, Map<String, Object>> upstream(String nodeId, JsonObject payload) {
		return Map.of(nodeId, Map.of("detections", payload.encode()));
	}

	static JsonObject detections(Integer imageWidth, Integer imageHeight, String coordinates, JsonObject... boxes) {
		io.vertx.core.json.JsonArray items = new io.vertx.core.json.JsonArray();
		for (JsonObject box : boxes) {
			items.add(box);
		}
		JsonObject payload = new JsonObject().put("coordinates", coordinates).put("detections", items);
		if (imageWidth != null && imageHeight != null) {
			payload.put("imageWidth", imageWidth).put("imageHeight", imageHeight);
		}
		return payload;
	}

	static JsonObject detection(int index, String label, double x, double y, double w, double h) {
		return new JsonObject()
			.put("index", index)
			.put("type", label)
			.put("label", label)
			.put("frame", 0)
			.put("bbox", new JsonObject().put("x", x).put("y", y).put("w", w).put("h", h))
			.put("confidence", 1.0d);
	}
}
