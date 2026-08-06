package io.metaloom.cortex.node.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.guard.GuardScoring.Score;

/**
 * The arithmetic every family's score goes through.
 *
 * <p>
 * Three things here are easy to get subtly wrong and impossible to notice in production, because
 * each of them produces a plausible number rather than an error: matching {@code safe} inside
 * {@code unsafe}, reading position 0 when the decision is at position 1, and forgetting to
 * renormalise over the two decision tokens.
 * </p>
 */
class GuardScoringTest {

	private static final List<String> UNSAFE = List.of("unsafe");

	private static final List<String> SAFE = List.of("safe");

	private static Map<String, Double> position(Object... tokenProbPairs) {
		Map<String, Double> map = new LinkedHashMap<>();
		for (int i = 0; i < tokenProbPairs.length; i += 2) {
			map.put((String) tokenProbPairs[i], (Double) tokenProbPairs[i + 1]);
		}
		return map;
	}

	@Test
	void testRenormalisesOverTheTwoDecisionTokens() {
		// Raw P(unsafe) is 0.30, but the model put 0.60 of its mass on unrelated tokens. Reading
		// 0.30 directly would make every model look systematically safer than it is.
		Score score = GuardScoring.score(
			new GuardCompletion("unsafe", List.of(position("unsafe", 0.30, "safe", 0.10))),
			UNSAFE, SAFE);

		assertThat(score.value()).isCloseTo(0.75, within(1e-9));
		assertThat(score.exact()).isTrue();
	}

	@Test
	void testUnsafeIsNotMatchedAsSafe() {
		// "unsafe".contains("safe") - a substring match here would score every unsafe verdict as
		// safe, silently, forever.
		Score score = GuardScoring.score(
			new GuardCompletion("unsafe", List.of(position("unsafe", 0.9))),
			UNSAFE, SAFE);

		assertThat(score.value()).isCloseTo(0.9, within(1e-9));
	}

	@Test
	void testOnlySafeInTopNGivesTheComplement() {
		Score score = GuardScoring.score(
			new GuardCompletion("safe", List.of(position("safe", 0.98))),
			UNSAFE, SAFE);

		assertThat(score.value()).isCloseTo(0.02, within(1e-9));
		assertThat(score.exact()).isTrue();
	}

	@Test
	void testLeadingNewlineDoesNotSwallowTheDecision() {
		// Reading position 0 would score the line break: neither candidate is there, so the value
		// would fall through to the argmax fallback even though real probabilities were available.
		Score score = GuardScoring.score(
			new GuardCompletion("\nunsafe", List.of(position("\n", 0.99), position("unsafe", 0.7, "safe", 0.3))),
			UNSAFE, SAFE);

		assertThat(score.value()).isCloseTo(0.7, within(1e-9));
		assertThat(score.exact()).isTrue();
	}

	@Test
	void testTokensAreStrippedAndCaseInsensitive() {
		Score score = GuardScoring.score(
			new GuardCompletion("Yes", List.of(position(" YES", 0.8, " no", 0.2))),
			List.of("yes"), List.of("no"));

		assertThat(score.value()).isCloseTo(0.8, within(1e-9));
	}

	@Test
	void testSearchStopsBeforeAnExplanationThatMentionsSafe() {
		// max_tokens keeps answers short, but a chatty build could still start explaining. The
		// search is bounded so it scores the decision, not a word in the reasoning.
		Score score = GuardScoring.score(
			new GuardCompletion("Yes", List.of(
				position("A", 0.9), position("B", 0.9), position("C", 0.9), position("unsafe", 0.9))),
			UNSAFE, SAFE);

		assertThat(score.exact()).isFalse();
	}

	@Test
	void testNoProbabilitiesFallsBackToTheFirstWord() {
		assertThat(GuardScoring.score(GuardCompletion.textOnly("unsafe\nS1,S12"), UNSAFE, SAFE))
			.satisfies(score -> {
				assertThat(score.value()).isEqualTo(1d);
				assertThat(score.exact()).isFalse();
			});
		assertThat(GuardScoring.score(GuardCompletion.textOnly("safe"), UNSAFE, SAFE).value()).isZero();
	}

	@Test
	void testFallbackReadsThroughAWrapperTag() {
		assertThat(GuardScoring.score(GuardCompletion.textOnly("<score>yes</score>"), List.of("yes"), List.of("no")).value())
			.isEqualTo(1d);
	}

	@Test
	void testEmptyAnswerIsScoredSafeRatherThanThrowing() {
		assertThat(GuardScoring.score(GuardCompletion.textOnly(""), UNSAFE, SAFE).value()).isZero();
	}
}
