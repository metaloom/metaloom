package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.core.node.filter.ThresholdFilterNode.Operator;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class ThresholdFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	private static ThresholdFilterNode filter(Operator operator, double threshold) {
		return ThresholdFilterNode.builder("threshold")
				.upstreamNodeId("scorer")
				.outputKey("confidence")
				.operator(operator)
				.threshold(threshold)
				.build();
	}

	private boolean passed(ThresholdFilterNode filter, Object value) {
		Map<String, NodeResult> upstream = value == null
				? upstream("scorer", Map.of())
				: upstream("scorer", Map.of("confidence", value));
		return evaluate(filter, MEDIA, upstream).<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testBuildRequiresUpstreamNodeIdOutputKeyAndOperator() {
		assertThatThrownBy(() -> ThresholdFilterNode.builder("t").outputKey("k").operator(Operator.GT).build())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("upstreamNodeId, outputKey, and operator are required");

		assertThatThrownBy(() -> ThresholdFilterNode.builder("t").upstreamNodeId("n").operator(Operator.GT).build())
				.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(() -> ThresholdFilterNode.builder("t").upstreamNodeId("n").outputKey("k").build())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void testThresholdDefaultsToZero() {
		ThresholdFilterNode filter = ThresholdFilterNode.builder("threshold")
				.upstreamNodeId("scorer")
				.outputKey("confidence")
				.operator(Operator.GT)
				.build();

		assertThat(passed(filter, 0.5)).isTrue();
		assertThat(passed(filter, -0.5)).isFalse();
	}

	@Test
	void testGtIsStrict() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.8);

		assertThat(passed(filter, 0.9)).isTrue();
		assertThat(passed(filter, 0.8)).isFalse();
		assertThat(passed(filter, 0.7)).isFalse();
	}

	@Test
	void testGteIncludesTheBoundary() {
		ThresholdFilterNode filter = filter(Operator.GTE, 0.8);

		assertThat(passed(filter, 0.8)).isTrue();
		assertThat(passed(filter, 0.79)).isFalse();
	}

	@Test
	void testLtIsStrict() {
		ThresholdFilterNode filter = filter(Operator.LT, 0.8);

		assertThat(passed(filter, 0.7)).isTrue();
		assertThat(passed(filter, 0.8)).isFalse();
	}

	@Test
	void testLteIncludesTheBoundary() {
		ThresholdFilterNode filter = filter(Operator.LTE, 0.8);

		assertThat(passed(filter, 0.8)).isTrue();
		assertThat(passed(filter, 0.81)).isFalse();
	}

	@Test
	void testEq() {
		ThresholdFilterNode filter = filter(Operator.EQ, 0.8);

		assertThat(passed(filter, 0.8)).isTrue();
		assertThat(passed(filter, 0.80001)).isFalse();
	}

	@Test
	void testAnyNumberSubtypeIsCompared() {
		ThresholdFilterNode filter = filter(Operator.GTE, 5.0);

		assertThat(passed(filter, 5)).isTrue();
		assertThat(passed(filter, 5L)).isTrue();
		assertThat(passed(filter, 5.0f)).isTrue();
		assertThat(passed(filter, 4)).isFalse();
	}

	/**
	 * Every degenerate input path fails open. This is deliberate — a filter that
	 * cannot see its input must not silently drop media — so it is pinned here
	 * rather than left to be rediscovered.
	 */
	@Test
	void testFailsOpenWhenTheValueCannotBeRead() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.8);

		assertThat(evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("no upstream results at all")
				.isTrue();
		assertThat(evaluate(filter, MEDIA, upstream("other-node", Map.of("confidence", 0.1)))
				.<Boolean> getOutput(PipelineNode.FILTER_PASSED))
				.as("upstream node id does not match")
				.isTrue();
		assertThat(passed(filter, null))
				.as("output key absent")
				.isTrue();
		assertThat(passed(filter, "0.1"))
				.as("value is a String, not a Number")
				.isTrue();
		assertThat(passed(filter, Boolean.FALSE))
				.as("value is not a Number")
				.isTrue();
	}

	@Test
	void testRejectReasonNamesTheSourceAndComparison() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.8);
		NodeResult result = evaluate(filter, MEDIA, upstream("scorer", Map.of("confidence", 0.1)));

		assertThat(result.<String> getOutput("filter_reason"))
				.isEqualTo("scorer:confidence failed GT 0.8");
	}

	@Test
	void testPassRoutesToPassBranch() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.5);
		FixedOutputNode scorer = new FixedOutputNode("scorer", Map.of("confidence", 0.9));

		PipelineResult result = route(MEDIA, filter, scorer);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("threshold", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRejectRoutesToRejectBranch() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.5);
		FixedOutputNode scorer = new FixedOutputNode("scorer", Map.of("confidence", 0.1));

		PipelineResult result = route(MEDIA, filter, scorer);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("threshold", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
