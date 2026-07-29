package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.core.node.filter.ThresholdFilterNode.Operator;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

class ThresholdFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	/** Stand-in scorer, emitting on the very port id the filter consumes. */
	private static final OutputPort<Double> OUT_CONFIDENCE =
		OutputPort.one(ThresholdFilterNode.IN_VALUE.id(), ContentTypeRegistry.SCALAR_NUMBER, Double.class);

	private static ThresholdFilterNode filter(Operator operator, double threshold) {
		return ThresholdFilterNode.builder("threshold")
				.operator(operator)
				.threshold(threshold)
				.build();
	}

	private boolean passed(ThresholdFilterNode filter, double value) {
		return passed(evaluate(filter, MEDIA, input(ThresholdFilterNode.IN_VALUE, value)));
	}

	/**
	 * Fill the value port with something the typed builder would not accept, to
	 * exercise what the read-side boundary does with it.
	 */
	private static NodeInputs rawValue(Object value) {
		return NodeInputs.of(Map.of(ThresholdFilterNode.IN_VALUE.id(),
				PortPayload.one(ContentTypeRegistry.SCALAR_NUMBER, Origin.single("test-item"), value)));
	}

	private static FixedOutputNode scorer(double confidence) {
		return new FixedOutputNode("scorer", Map.of(OUT_CONFIDENCE.id(), PortOutput.one(OUT_CONFIDENCE, confidence)));
	}

	@Test
	void testBuildRequiresAnOperator() {
		assertThatThrownBy(() -> ThresholdFilterNode.builder("t").threshold(0.5).build())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("An operator is required");
	}

	@Test
	void testThresholdDefaultsToZero() {
		ThresholdFilterNode filter = ThresholdFilterNode.builder("threshold")
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

	/**
	 * {@code scalar/number} is always read as a {@code Double}, whatever the producer
	 * put on the wire — a whole number that a JSON round trip narrowed to
	 * {@code Integer}, or a value some cache stringified. That widening is what stops
	 * this filter from having to defend against {@code Number} subtypes itself.
	 */
	@Test
	void testWholeNumbersAndStringifiedNumbersAreWidenedOnRead() {
		ThresholdFilterNode filter = filter(Operator.GTE, 5.0);

		assertThat(passed(evaluate(filter, MEDIA, rawValue(5)))).as("Integer").isTrue();
		assertThat(passed(evaluate(filter, MEDIA, rawValue(5L)))).as("Long").isTrue();
		assertThat(passed(evaluate(filter, MEDIA, rawValue(5.0f)))).as("Float").isTrue();
		assertThat(passed(evaluate(filter, MEDIA, rawValue("5.0")))).as("stringified").isTrue();
		assertThat(passed(evaluate(filter, MEDIA, rawValue(4)))).as("below the threshold").isFalse();
	}

	/**
	 * An unwired value port fails open. This is deliberate — a filter that cannot see
	 * its input must not silently drop media — so it is pinned here rather than left
	 * to be rediscovered.
	 */
	@Test
	void testFailsOpenWhenNothingIsWired() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.8);

		assertThat(passed(evaluate(filter, MEDIA)))
				.as("nothing wired into the value port")
				.isTrue();
	}

	/**
	 * A value that is not a number at all no longer slips through as "unreadable, so
	 * pass": the port coerces on read and the filter fails, which surfaces the
	 * mis-wiring instead of hiding it behind a green pass.
	 */
	@Test
	void testANonNumericValueFailsTheNode() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.8);

		NodeResult result = evaluate(filter, MEDIA, rawValue(Boolean.FALSE));

		assertThat(result.getState()).isEqualTo(ResultState.FAILED);
		assertThat(result.has(AbstractFilterNode.OUT_PASSED))
				.as("no verdict was emitted, so neither branch can claim the item")
				.isFalse();
	}

	@Test
	void testPassRoutesToPassBranch() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.5);

		PipelineResult result = route(MEDIA, filter, scorer(0.9));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("threshold", AbstractFilterNode.OUT_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRejectRoutesToRejectBranch() {
		ThresholdFilterNode filter = filter(Operator.GT, 0.5);

		PipelineResult result = route(MEDIA, filter, scorer(0.1));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("threshold", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
