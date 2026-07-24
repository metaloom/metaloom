package io.metaloom.cortex.pipeline.core.node.filter;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

class SamplingFilterNodeTest extends AbstractFilterNodeTest {

	private static final StubLoomMedia MEDIA = new StubLoomMedia("/media/photo.jpg", false, true, false, false);

	private boolean passed(SamplingFilterNode filter) {
		return evaluate(filter, MEDIA).<Boolean> getOutput(PipelineNode.FILTER_PASSED);
	}

	@Test
	void testPassRateIsRangeChecked() {
		assertThatThrownBy(() -> SamplingFilterNode.builder("sample").passRate(-0.1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("passRate must be between 0.0 and 1.0");

		assertThatThrownBy(() -> SamplingFilterNode.builder("sample").passRate(1.1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * {@code Random.nextDouble()} yields {@code [0.0, 1.0)} and the comparison is
	 * strict {@code <}, so both endpoints are absolute rather than merely likely.
	 */
	@Test
	void testPassRateZeroRejectsEverything() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample").passRate(0.0).build();

		for (int i = 0; i < 100; i++) {
			assertThat(passed(filter)).isFalse();
		}
	}

	@Test
	void testPassRateOnePassesEverything() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample").passRate(1.0).build();

		for (int i = 0; i < 100; i++) {
			assertThat(passed(filter)).isTrue();
		}
	}

	/**
	 * With a seed the decision sequence is exactly {@code new Random(seed)}'s
	 * {@code nextDouble()} sequence — it is <em>not</em> derived from the media,
	 * so the same file processed twice can get different answers.
	 */
	@Test
	void testSeededSequenceMatchesAPlainRandom() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample")
				.passRate(0.5)
				.seed(42L)
				.build();
		Random oracle = new Random(42L);

		for (int i = 0; i < 50; i++) {
			assertThat(passed(filter))
					.as("decision %d", i)
					.isEqualTo(oracle.nextDouble() < 0.5);
		}
	}

	@Test
	void testSameSeedGivesTwoNodesTheSameSequence() {
		SamplingFilterNode a = SamplingFilterNode.builder("a").passRate(0.5).seed(7L).build();
		SamplingFilterNode b = SamplingFilterNode.builder("b").passRate(0.5).seed(7L).build();

		for (int i = 0; i < 50; i++) {
			assertThat(passed(a)).isEqualTo(passed(b));
		}
	}

	@Test
	void testDecisionIsNotStablePerMediaItem() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample").passRate(0.5).seed(1L).build();

		boolean[] decisions = new boolean[20];
		for (int i = 0; i < decisions.length; i++) {
			decisions[i] = passed(filter);
		}

		boolean allSame = true;
		for (boolean d : decisions) {
			allSame &= (d == decisions[0]);
		}
		assertThat(allSame)
				.as("the same media evaluated repeatedly must not yield a constant answer")
				.isFalse();
	}

	@Test
	void testRejectReasonCarriesTheRate() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample").passRate(0.0).build();
		NodeResult result = evaluate(filter, MEDIA);

		assertThat(result.<String> getOutput("filter_reason"))
				.isEqualTo("rejected by sampling (rate=0.0)");
	}

	@Test
	void testPassRoutesToPassBranch() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample").passRate(1.0).build();

		PipelineResult result = route(MEDIA, filter);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("sample", PipelineNode.FILTER_PASSED, true);
		assertThat(result).node(PASS_NODE).isCompleted();
		assertThat(result).node(REJECT_NODE).isSkipped();
	}

	@Test
	void testRejectRoutesToRejectBranch() {
		SamplingFilterNode filter = SamplingFilterNode.builder("sample").passRate(0.0).build();

		PipelineResult result = route(MEDIA, filter);

		assertThat(result)
				.isSuccess()
				.hasNodeOutput("sample", PipelineNode.FILTER_PASSED, false);
		assertThat(result).node(REJECT_NODE).isCompleted();
		assertThat(result).node(PASS_NODE).isSkipped();
	}
}
