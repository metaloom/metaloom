package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.vertx.core.json.JsonObject;

class QualityFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	/**
	 * Stand-in for the quality node's metric set. One structured payload rather than a
	 * metric-per-output: the filter reads the whole set from a single port, so a test
	 * that filled ports one metric at a time would not be exercising the real wiring.
	 */
	private static final OutputPort<String> OUT_METRICS =
		OutputPort.one(QualityFilterNode.IN_QUALITY.id(), ContentTypeRegistry.STRUCT_QUALITY, String.class);

	private boolean passed(QualityFilterNode filter, JsonObject metrics) {
		return passed(evaluate(filter, MEDIA, input(QualityFilterNode.IN_QUALITY, metrics.encode())));
	}

	private static FixedOutputNode quality(JsonObject metrics) {
		return new FixedOutputNode("quality",
				Map.of(OUT_METRICS.id(), PortOutput.one(OUT_METRICS, metrics.encode())));
	}

	@Test
	void testBlurrinessAboveMaxIsRejected() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.maxBlurriness(0.5)
				.build();

		assertThat(passed(filter, new JsonObject().put("blurriness", 0.9))).isFalse();
		assertThat(passed(filter, new JsonObject().put("blurriness", 0.1))).isTrue();
		assertThat(passed(filter, new JsonObject().put("blurriness", 0.5)))
				.as("the bound is inclusive")
				.isTrue();
	}

	/**
	 * The metric set carries one width/height pair. The former {@code image_*} /
	 * {@code video_*} split is gone — the quality node emits the dimensions of
	 * whatever it decoded, so there is no precedence rule left to test.
	 */
	@Test
	void testResolutionBelowMinimumIsRejected() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.minWidth(1920)
				.minHeight(1080)
				.build();

		assertThat(passed(filter, new JsonObject().put("width", 1920L).put("height", 1080L)))
				.as("exactly at the minimum")
				.isTrue();
		assertThat(passed(filter, new JsonObject().put("width", 1280L).put("height", 1080L))).isFalse();
		assertThat(passed(filter, new JsonObject().put("width", 1920L).put("height", 720L))).isFalse();
	}

	@Test
	void testAbsentDimensionsSkipTheResolutionCheck() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.minWidth(1920)
				.build();

		assertThat(passed(filter, new JsonObject().put("height", 1080L)))
				.as("width was not measured, so nothing can be compared against it")
				.isTrue();
	}

	@Test
	void testQualityFlagMustBeTheExactSuccessString() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.requireQualityFlag(true)
				.build();

		assertThat(passed(filter, new JsonObject().put("flag", "SUCCESS"))).isTrue();
		assertThat(passed(filter, new JsonObject().put("flag", "success")))
				.as("comparison is case-sensitive")
				.isFalse();
		assertThat(passed(filter, new JsonObject().put("flag", "FAILED"))).isFalse();
		assertThat(passed(filter, new JsonObject().put("blurriness", 0.1)))
				.as("flag required but absent from a non-empty metric set")
				.isFalse();
	}

	/**
	 * The filter short-circuits to PASS the moment the metric set is empty —
	 * including when {@code requireQualityFlag} is set. An unwired quality port
	 * therefore disables the filter entirely rather than rejecting everything.
	 */
	@Test
	void testEmptyQualityMetricsShortCircuitToPass() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.requireQualityFlag(true)
				.maxBlurriness(0.0)
				.minWidth(99999)
				.build();

		assertThat(passed(evaluate(filter, MEDIA)))
				.as("nothing wired into the quality port")
				.isTrue();
		assertThat(passed(filter, new JsonObject()))
				.as("the quality node ran but measured nothing")
				.isTrue();
	}

	@Test
	void testNonNumericMetricsSkipTheirCheck() {
		QualityFilterNode filter = QualityFilterNode.builder("quality")
				.maxBlurriness(0.5)
				.minWidth(1920)
				.build();

		assertThat(passed(filter, new JsonObject().put("blurriness", "0.9").put("width", "640")))
				.as("the metric set is decoded as-is, and String values are not Numbers")
				.isTrue();
	}

	@Test
	void testSharpImageRoutesToPassBranch() {
		QualityFilterNode filter = QualityFilterNode.builder("quality-filter")
				.maxBlurriness(0.5)
				.build();

		PipelineResult result = route(MEDIA, filter, quality(new JsonObject().put("blurriness", 0.1)));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("quality-filter", AbstractFilterNode.OUT_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testBlurryImageRoutesToRejectBranch() {
		QualityFilterNode filter = QualityFilterNode.builder("quality-filter")
				.maxBlurriness(0.5)
				.build();

		PipelineResult result = route(MEDIA, filter, quality(new JsonObject().put("blurriness", 0.9)));

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("quality-filter", AbstractFilterNode.OUT_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
