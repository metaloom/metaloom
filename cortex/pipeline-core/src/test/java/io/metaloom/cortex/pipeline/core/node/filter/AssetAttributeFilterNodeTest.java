package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class AssetAttributeFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/clip.mp4", true, false, false, false);

	@TempDir
	File tempDir;

	private boolean passed(AssetAttributeFilterNode filter, Map<String, Object> probeOutputs) {
		return evaluate(filter, MEDIA, upstream("probe", probeOutputs))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testUnconfiguredFilterPassesEverything() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs").build();

		assertThat(evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isTrue();
	}

	@Test
	void testFileSizeBoundsAreInclusive() {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "ten-bytes.bin", "0123456789");

		AssetAttributeFilterNode atMin = AssetAttributeFilterNode.builder("attrs").minFileSize(10L).build();
		AssetAttributeFilterNode atMax = AssetAttributeFilterNode.builder("attrs").maxFileSize(10L).build();
		AssetAttributeFilterNode tooSmall = AssetAttributeFilterNode.builder("attrs").minFileSize(11L).build();
		AssetAttributeFilterNode tooLarge = AssetAttributeFilterNode.builder("attrs").maxFileSize(9L).build();

		assertThat(evaluate(atMin, media).<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isTrue();
		assertThat(evaluate(atMax, media).<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isTrue();
		assertThat(evaluate(tooSmall, media).<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isFalse();
		assertThat(evaluate(tooLarge, media).<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isFalse();
	}

	@Test
	void testResolutionBoundsFromUpstreamOutputs() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minWidth(1280)
				.maxWidth(3840)
				.minHeight(720)
				.maxHeight(2160)
				.build();

		assertThat(passed(filter, Map.of("video_width", 1920, "video_height", 1080))).isTrue();
		assertThat(passed(filter, Map.of("video_width", 640, "video_height", 1080))).isFalse();
		assertThat(passed(filter, Map.of("video_width", 7680, "video_height", 1080))).isFalse();
		assertThat(passed(filter, Map.of("video_width", 1920, "video_height", 480))).isFalse();
		assertThat(passed(filter, Map.of("video_width", 1920, "video_height", 4320))).isFalse();
	}

	@Test
	void testImageDimensionsTakePrecedenceOverVideoDimensions() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.maxWidth(1000)
				.build();

		assertThat(passed(filter, Map.of("image_width", 800, "video_width", 3840)))
				.as("image_width wins and is within bounds")
				.isTrue();
		assertThat(passed(filter, Map.of("video_width", 3840)))
				.as("video_width is the fallback")
				.isFalse();
	}

	@Test
	void testFpsBounds() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minFps(24.0)
				.maxFps(60.0)
				.build();

		assertThat(passed(filter, Map.of("video_fps", 30.0))).isTrue();
		assertThat(passed(filter, Map.of("video_fps", 12.0))).isFalse();
		assertThat(passed(filter, Map.of("video_fps", 120.0))).isFalse();
	}

	@Test
	void testBitrateBounds() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minBitrate(1_000_000L)
				.build();

		assertThat(passed(filter, Map.of("bitrate", 5_000_000L))).isTrue();
		assertThat(passed(filter, Map.of("bitrate", 128_000L))).isFalse();
	}

	@Test
	void testAudioChannelBounds() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minAudioChannels(2)
				.maxAudioChannels(2)
				.build();

		assertThat(passed(filter, Map.of("audio_channels", 2))).as("stereo").isTrue();
		assertThat(passed(filter, Map.of("audio_channels", 1))).as("mono").isFalse();
		assertThat(passed(filter, Map.of("audio_channels", 6))).as("5.1").isFalse();
	}

	/**
	 * Duration is not a media attribute — it is derived as
	 * {@code video_frame_count / video_fps} from the upstream probe.
	 */
	@Test
	void testDurationIsDerivedFromFrameCountAndFps() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minDurationSeconds(10.0)
				.maxDurationSeconds(60.0)
				.build();

		assertThat(passed(filter, Map.of("video_frame_count", 900, "video_fps", 30.0)))
				.as("30 seconds")
				.isTrue();
		assertThat(passed(filter, Map.of("video_frame_count", 60, "video_fps", 30.0)))
				.as("2 seconds is under the minimum")
				.isFalse();
		assertThat(passed(filter, Map.of("video_frame_count", 5400, "video_fps", 30.0)))
				.as("180 seconds is over the maximum")
				.isFalse();
	}

	@Test
	void testDurationCheckIsSkippedWhenFpsIsZeroOrMissing() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minDurationSeconds(10.0)
				.build();

		assertThat(passed(filter, Map.of("video_frame_count", 60, "video_fps", 0.0)))
				.as("fps of zero cannot yield a duration")
				.isTrue();
		assertThat(passed(filter, Map.of("video_frame_count", 60)))
				.as("no fps at all")
				.isTrue();
	}

	/**
	 * Every upstream-derived check fails open on missing or non-numeric data.
	 * Only the file-size check, which reads the filesystem directly, can reject
	 * without upstream input.
	 */
	@Test
	void testMissingAndNonNumericUpstreamDataSkipTheirChecks() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minWidth(1920)
				.minFps(24.0)
				.minBitrate(1_000_000L)
				.minAudioChannels(2)
				.build();

		assertThat(evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("no upstream results at all")
				.isTrue();
		assertThat(evaluate(filter, MEDIA, upstream("wrong-node", Map.of("video_width", 640)))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("qualityNodeId does not match")
				.isTrue();
		assertThat(passed(filter, Map.of("video_width", "640", "video_fps", "12", "bitrate", "1", "audio_channels", "1")))
				.as("String values are not Numbers")
				.isTrue();
	}

	@Test
	void testUnconfiguredQualityNodeIdIgnoresUpstreamEntirely() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.minWidth(1920)
				.build();

		assertThat(passed(filter, Map.of("video_width", 640))).isTrue();
	}

	@Test
	void testRejectReason() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minWidth(1920)
				.build();
		NodeResult result = evaluate(filter, MEDIA, upstream("probe", Map.of("video_width", 640)));

		assertThat(result.<String> getOutput("filter_reason")).isEqualTo("asset attributes out of range");
	}

	@Test
	void testInRangeAssetRoutesToPassBranch() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minWidth(1280)
				.build();
		FixedOutputNode probe = new FixedOutputNode("probe", Map.of("video_width", 1920, "video_height", 1080));

		PipelineResult result = route(MEDIA, filter, probe);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("attrs", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testOutOfRangeAssetRoutesToRejectBranch() {
		AssetAttributeFilterNode filter = AssetAttributeFilterNode.builder("attrs")
				.qualityNodeId("probe")
				.minWidth(1280)
				.build();
		FixedOutputNode probe = new FixedOutputNode("probe", Map.of("video_width", 320, "video_height", 240));

		PipelineResult result = route(MEDIA, filter, probe);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("attrs", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
