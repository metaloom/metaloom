package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class QualityFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	private boolean passed(QualityFilterNode filter, Map<String, Object> qualityOutputs) {
		return evaluate(filter, MEDIA, upstream("quality", qualityOutputs))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testBlurrinessAboveMaxIsRejected() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality-node")
				.maxBlurriness(0.5)
				.build();

		assertThat(evaluate(filter, MEDIA, upstream("quality-node", Map.of("blurriness", 0.9)))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isFalse();
		assertThat(evaluate(filter, MEDIA, upstream("quality-node", Map.of("blurriness", 0.1)))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED)).isTrue();
		assertThat(evaluate(filter, MEDIA, upstream("quality-node", Map.of("blurriness", 0.5)))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("the bound is inclusive")
				.isTrue();
	}

	@Test
	void testResolutionBelowMinimumIsRejected() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality")
				.minWidth(1920)
				.minHeight(1080)
				.build();

		assertThat(passed(filter, Map.of("image_width", 1920, "image_height", 1080)))
				.as("exactly at the minimum")
				.isTrue();
		assertThat(passed(filter, Map.of("image_width", 1280, "image_height", 1080))).isFalse();
		assertThat(passed(filter, Map.of("image_width", 1920, "image_height", 720))).isFalse();
	}

	@Test
	void testVideoDimensionsAreUsedWhenImageDimensionsAreAbsent() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality")
				.minWidth(1920)
				.build();

		assertThat(passed(filter, Map.of("video_width", 3840))).isTrue();
		assertThat(passed(filter, Map.of("video_width", 640))).isFalse();
	}

	@Test
	void testQualityFlagMustBeTheExactSuccessString() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality")
				.requireQualityFlag(true)
				.build();

		assertThat(passed(filter, Map.of("quality_flag", "SUCCESS"))).isTrue();
		assertThat(passed(filter, Map.of("quality_flag", "success"))).as("comparison is case-sensitive").isFalse();
		assertThat(passed(filter, Map.of("quality_flag", "FAILED"))).isFalse();
		assertThat(passed(filter, Map.of("blurriness", 0.1)))
				.as("flag required but absent from a non-empty output map")
				.isFalse();
	}

	/**
	 * The filter short-circuits to PASS the moment the upstream output map is
	 * empty — including when {@code requireQualityFlag} is set. A misconfigured
	 * {@code qualityNodeId} therefore disables the filter entirely rather than
	 * rejecting everything.
	 */
	@Test
	void testEmptyQualityOutputsShortCircuitToPass() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality")
				.requireQualityFlag(true)
				.maxBlurriness(0.0)
				.minWidth(99999)
				.build();

		assertThat(evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("no upstream results at all")
				.isTrue();
		assertThat(evaluate(filter, MEDIA, upstream("wrong-node", Map.of("quality_flag", "FAILED")))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("qualityNodeId does not match any upstream node")
				.isTrue();
		assertThat(passed(filter, Map.of()))
				.as("upstream ran but produced nothing")
				.isTrue();
	}

	@Test
	void testUnconfiguredQualityNodeIdPassesEverything() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.requireQualityFlag(true)
				.build();

		assertThat(passed(filter, Map.of("quality_flag", "FAILED"))).isTrue();
	}

	@Test
	void testNonNumericValuesSkipTheirCheck() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality")
				.maxBlurriness(0.5)
				.minWidth(1920)
				.build();

		assertThat(passed(filter, Map.of("blurriness", "0.9", "image_width", "640")))
				.as("String values are not Numbers, so both checks are skipped")
				.isTrue();
	}

	@Test
	void testRejectReason() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.qualityNodeId("quality")
				.maxBlurriness(0.5)
				.build();
		NodeResult result = evaluate(filter, MEDIA, upstream("quality", Map.of("blurriness", 0.9)));

		assertThat(result.<String> getOutput("filter_reason")).isEqualTo("quality below threshold");
	}

	@Test
	void testSharpImageRoutesToPassBranch() {
		QualityFilterNode filter = QualityFilterNode.builder("quality-filter")
				.qualityNodeId("quality")
				.maxBlurriness(0.5)
				.build();
		FixedOutputNode quality = new FixedOutputNode("quality", Map.of("blurriness", 0.1));

		PipelineResult result = route(MEDIA, filter, quality);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("quality-filter", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testBlurryImageRoutesToRejectBranch() {
		QualityFilterNode filter = QualityFilterNode.builder("quality-filter")
				.qualityNodeId("quality")
				.maxBlurriness(0.5)
				.build();
		FixedOutputNode quality = new FixedOutputNode("quality", Map.of("blurriness", 0.9));

		PipelineResult result = route(MEDIA, filter, quality);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("quality-filter", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
