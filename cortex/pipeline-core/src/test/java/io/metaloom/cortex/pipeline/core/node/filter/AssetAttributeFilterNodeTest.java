package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.vertx.core.json.JsonObject;

class AssetAttributeFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/clip.mp4", true, false, false, false);

	/** Stand-in for the quality node's metric set, on the port id the filter consumes. */
	private static final OutputPort<String> OUT_METRICS =
		OutputPort.one(AssetAttributeFilterNode.IN_QUALITY.id(), ContentTypeRegistry.STRUCT_QUALITY, String.class);

	@TempDir
	File tempDir;

	private boolean passed(AssetAttributeFilterNode filter, JsonObject metrics) {
		return passed(evaluate(filter, MEDIA, input(AssetAttributeFilterNode.IN_QUALITY, metrics.encode())));
	}

	private static FixedOutputNode probe(JsonObject metrics) {
		return new FixedOutputNode("probe",
				Map.of(OUT_METRICS.id(), PortOutput.one(OUT_METRICS, metrics.encode())));
	}

	@Test
	void testUnconfiguredFilterPassesEverything() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs").build();

		assertThat(passed(evaluate(filter, MEDIA))).isTrue();
	}

	@Test
	void testFileSizeBoundsAreInclusive() {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "ten-bytes.bin", "0123456789");

		AssetAttributeFilterNode atMin = AssetAttributeFilterNode.builder("attrs").minFileSize(10L).build();
		AssetAttributeFilterNode atMax = AssetAttributeFilterNode.builder("attrs").maxFileSize(10L).build();
		AssetAttributeFilterNode tooSmall = AssetAttributeFilterNode.builder("attrs").minFileSize(11L).build();
		AssetAttributeFilterNode tooLarge = AssetAttributeFilterNode.builder("attrs").maxFileSize(9L).build();

		assertThat(passed(evaluate(atMin, media))).isTrue();
		assertThat(passed(evaluate(atMax, media))).isTrue();
		assertThat(passed(evaluate(tooSmall, media))).isFalse();
		assertThat(passed(evaluate(tooLarge, media))).isFalse();
	}

	/**
	 * The metric set carries one width/height pair, whatever the asset is. The former
	 * {@code image_*} / {@code video_*} split - and with it the precedence rule
	 * between the two - no longer exists.
	 */
	@Test
	void testResolutionBoundsFromTheMetricSet() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minWidth(1280)
				.maxWidth(3840)
				.minHeight(720)
				.maxHeight(2160)
				.build();

		assertThat(passed(filter, new JsonObject().put("width", 1920L).put("height", 1080L))).isTrue();
		assertThat(passed(filter, new JsonObject().put("width", 640L).put("height", 1080L))).isFalse();
		assertThat(passed(filter, new JsonObject().put("width", 7680L).put("height", 1080L))).isFalse();
		assertThat(passed(filter, new JsonObject().put("width", 1920L).put("height", 480L))).isFalse();
		assertThat(passed(filter, new JsonObject().put("width", 1920L).put("height", 4320L))).isFalse();
	}

	@Test
	void testFpsBounds() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minFps(24.0)
				.maxFps(60.0)
				.build();

		assertThat(passed(filter, new JsonObject().put("fps", 30.0))).isTrue();
		assertThat(passed(filter, new JsonObject().put("fps", 12.0))).isFalse();
		assertThat(passed(filter, new JsonObject().put("fps", 120.0))).isFalse();
	}

	/**
	 * {@code bitrate} is read from the metric set like any other key. No node in the
	 * tree emits it today, so this pins the filter's own arithmetic rather than a
	 * wiring that exists - a producer that starts emitting it must match this shape.
	 */
	@Test
	void testBitrateBounds() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minBitrate(1_000_000L)
				.build();

		assertThat(passed(filter, new JsonObject().put("bitrate", 5_000_000L))).isTrue();
		assertThat(passed(filter, new JsonObject().put("bitrate", 128_000L))).isFalse();
	}

	/** As with {@code bitrate}: read from the metric set, not yet emitted by anything. */
	@Test
	void testAudioChannelBounds() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minAudioChannels(2)
				.maxAudioChannels(2)
				.build();

		assertThat(passed(filter, new JsonObject().put("audio_channels", 2L))).as("stereo").isTrue();
		assertThat(passed(filter, new JsonObject().put("audio_channels", 1L))).as("mono").isFalse();
		assertThat(passed(filter, new JsonObject().put("audio_channels", 6L))).as("5.1").isFalse();
	}

	/**
	 * Duration is not a media attribute — it is derived as
	 * {@code frame_count / fps} from the metric set.
	 */
	@Test
	void testDurationIsDerivedFromFrameCountAndFps() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minDurationSeconds(10.0)
				.maxDurationSeconds(60.0)
				.build();

		assertThat(passed(filter, new JsonObject().put("frame_count", 900L).put("fps", 30.0)))
				.as("30 seconds")
				.isTrue();
		assertThat(passed(filter, new JsonObject().put("frame_count", 60L).put("fps", 30.0)))
				.as("2 seconds is under the minimum")
				.isFalse();
		assertThat(passed(filter, new JsonObject().put("frame_count", 5400L).put("fps", 30.0)))
				.as("180 seconds is over the maximum")
				.isFalse();
	}

	@Test
	void testDurationCheckIsSkippedWhenFpsIsZeroOrMissing() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minDurationSeconds(10.0)
				.build();

		assertThat(passed(filter, new JsonObject().put("frame_count", 60L).put("fps", 0.0)))
				.as("fps of zero cannot yield a duration")
				.isTrue();
		assertThat(passed(filter, new JsonObject().put("frame_count", 60L)))
				.as("no fps at all")
				.isTrue();
	}

	/**
	 * Every metric-derived check fails open on missing or non-numeric data. Only the
	 * file-size check, which reads the filesystem directly, can reject without a
	 * metric set.
	 */
	@Test
	void testMissingAndNonNumericMetricsSkipTheirChecks() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minWidth(1920)
				.minFps(24.0)
				.minBitrate(1_000_000L)
				.minAudioChannels(2)
				.build();

		assertThat(passed(evaluate(filter, MEDIA)))
				.as("nothing wired into the quality port")
				.isTrue();
		assertThat(passed(filter, new JsonObject()))
				.as("the quality node ran but measured nothing")
				.isTrue();
		assertThat(passed(filter, new JsonObject()
				.put("width", "640")
				.put("fps", "12")
				.put("bitrate", "1")
				.put("audio_channels", "1")))
				.as("the metric set is decoded as-is, and String values are not Numbers")
				.isTrue();
	}

	@Test
	void testInRangeAssetRoutesToPassBranch() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minWidth(1280)
				.build();

		PipelineResult result = route(MEDIA, filter,
				probe(new JsonObject().put("width", 1920L).put("height", 1080L)));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("attrs", AbstractFilterNode.OUT_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testOutOfRangeAssetRoutesToRejectBranch() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minWidth(1280)
				.build();

		PipelineResult result = route(MEDIA, filter,
				probe(new JsonObject().put("width", 320L).put("height", 240L)));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("attrs", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
