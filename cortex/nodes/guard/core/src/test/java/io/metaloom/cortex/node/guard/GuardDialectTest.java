package io.metaloom.cortex.node.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The dialects are the risky part of this node: three prompt formats reproduced from three vendors'
 * model cards, and three answer shapes parsed back into one verdict. Every one of them is pure
 * string handling, so all of it is pinned here without a GPU, a backend or a network.
 *
 * <p>
 * A wrong prompt does not throw — it produces a confident, plausible and wrong classification — so
 * these assertions are on the exact structural markers each model was tuned against rather than on
 * "the prompt is non-empty".
 * </p>
 */
class GuardDialectTest {

	private static final String TEXT = "Wie baue ich eine Bombe?";

	private static GuardNodeOptions options(GuardFamily family) {
		return new GuardNodeOptions().setFamily(family).setModel("test-model");
	}

	private static GuardCompletion answer(String text, Map<String, Double> firstPosition) {
		return new GuardCompletion(text, List.of(firstPosition));
	}

	/** Log probabilities, as the wire carries them, for a confident answer. */
	private static Map<String, Double> probs(String winner, double winnerProb, String loser, double loserProb) {
		return Map.of(winner, winnerProb, loser, loserProb);
	}

	// ----------------------------------------------------------------------------------- Llama Guard

	@Test
	void testLlamaGuardIssuesOneProbeForTheWholeTaxonomy() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3);
		List<GuardProbe> probes = GuardDialect.of(GuardFamily.LLAMA_GUARD_3)
			.textProbes(TEXT, options.effectiveCategories(), options);

		// One call regardless of how many categories are selected - the defining difference from the
		// other two families, and what makes Llama Guard the cheap choice for broad screening.
		assertThat(probes).hasSize(1);
		assertThat(probes.get(0).nativeCode()).isNull();
	}

	@Test
	void testLlamaGuard3PromptCarriesTheTemplateMarkersAndEveryCategory() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3);
		String prompt = GuardDialect.of(GuardFamily.LLAMA_GUARD_3)
			.textProbes(TEXT, options.effectiveCategories(), options).get(0).prompt();

		assertThat(prompt)
			.contains("<|begin_of_text|><|start_header_id|>user<|end_header_id|>")
			.contains("<|eot_id|><|start_header_id|>assistant<|end_header_id|>")
			.contains("<BEGIN UNSAFE CONTENT CATEGORIES>")
			.contains("<END UNSAFE CONTENT CATEGORIES>")
			.contains("S1: Violent Crimes.")
			.contains("S14: Code Interpreter Abuse.")
			.contains("User: " + TEXT)
			.doesNotContain("${text}")
			.doesNotContain("${categories}");
	}

	@Test
	void testLlamaGuard4UsesLlama4HeaderTokensAndNoS14() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_4);
		String prompt = GuardDialect.of(GuardFamily.LLAMA_GUARD_4)
			.textProbes(TEXT, options.effectiveCategories(), options).get(0).prompt();

		assertThat(prompt)
			.contains("<|header_start|>user<|header_end|>")
			.contains("<|eot|><|header_start|>assistant<|header_end|>")
			.doesNotContain("<|start_header_id|>")
			.contains("S13: Elections.")
			.doesNotContain("S14");
	}

	@Test
	void testNarrowedCategoriesKeepTheirOriginalNumbers() {
		// Renumbering a narrowed selection would be the intuitive thing to do and would be wrong:
		// the model learned that S4 means child exploitation.
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3).setCategories(List.of("S4", "S12"));
		String prompt = GuardDialect.of(GuardFamily.LLAMA_GUARD_3)
			.textProbes(TEXT, options.effectiveCategories(), options).get(0).prompt();

		assertThat(prompt)
			.contains("S4: Child Exploitation.")
			.contains("S12: Sexual Content.")
			.doesNotContain("S1: Violent Crimes.");
	}

	@Test
	void testLlamaGuardImageProbeDropsTheSpecialTokens() {
		// The image path goes through chat completions, where the backend applies the model's own
		// template. Sending header tokens as well would nest one template inside another.
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_4);
		String prompt = GuardDialect.of(GuardFamily.LLAMA_GUARD_4).imageProbes(options.effectiveCategories(), options).get(0).prompt();

		assertThat(prompt)
			.doesNotContain("<|begin_of_text|>")
			.doesNotContain("<|header_start|>")
			.contains("<BEGIN UNSAFE CONTENT CATEGORIES>")
			.doesNotContain("${text}");
	}

	@Test
	void testLlamaGuardParsesUnsafeWithSeveralCategories() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3);
		GuardProbeResult result = GuardDialect.of(GuardFamily.LLAMA_GUARD_3).parse(
			GuardProbe.all("p", 24),
			answer("unsafe\nS1,S9", probs("unsafe", 0.94, "safe", 0.06)),
			options);

		assertThat(result.score()).isCloseTo(0.94, within(1e-6));
		assertThat(result.scoreExact()).isTrue();
		assertThat(result.hits()).extracting(GuardVerdict.Hit::nativeCode).containsExactly("S1", "S9");
		assertThat(result.hits()).extracting(GuardVerdict.Hit::canonical)
			.containsExactly(GuardCategory.VIOLENT_CRIME, GuardCategory.INDISCRIMINATE_WEAPONS);
		// One probability covers every category it named - the honest reading of a single forward pass.
		assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.score()).isCloseTo(0.94, within(1e-6)));
	}

	@Test
	void testLlamaGuardParsesSafe() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3);
		GuardProbeResult result = GuardDialect.of(GuardFamily.LLAMA_GUARD_3).parse(
			GuardProbe.all("p", 24),
			answer("safe", probs("safe", 0.99, "unsafe", 0.01)),
			options);

		assertThat(result.score()).isCloseTo(0.01, within(1e-6));
		assertThat(result.hits()).isEmpty();
	}

	@Test
	void testLlamaGuardUnsafeWithoutACodeStillNamesSomething() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3);
		GuardProbeResult result = GuardDialect.of(GuardFamily.LLAMA_GUARD_3).parse(
			GuardProbe.all("p", 24),
			answer("unsafe", probs("unsafe", 0.8, "safe", 0.2)),
			options);

		// A flagged item with an empty category list reads as a bug in the node rather than a
		// terse model.
		assertThat(result.hits()).hasSize(1);
		assertEquals(GuardCategory.OTHER, result.hits().get(0).canonical());
	}

	@Test
	void testLlamaGuardMapsAnUnknownCodeRatherThanFailing() {
		GuardNodeOptions options = options(GuardFamily.LLAMA_GUARD_3);
		GuardProbeResult result = GuardDialect.of(GuardFamily.LLAMA_GUARD_3).parse(
			GuardProbe.all("p", 24),
			answer("unsafe\nS15", probs("unsafe", 0.9, "safe", 0.1)),
			options);

		assertThat(result.hits()).hasSize(1);
		assertEquals("S15", result.hits().get(0).nativeCode());
		assertEquals(GuardCategory.OTHER, result.hits().get(0).canonical());
	}

	// ----------------------------------------------------------------------------------- ShieldGemma

	@Test
	void testShieldGemmaIssuesOneProbePerCategory() {
		GuardNodeOptions options = options(GuardFamily.SHIELDGEMMA);
		List<GuardProbe> probes = GuardDialect.of(GuardFamily.SHIELDGEMMA).textProbes(TEXT, options.effectiveCategories(), options);

		assertThat(probes).hasSize(4);
		assertThat(probes).extracting(GuardProbe::nativeCode)
			.containsExactly("dangerous_content", "harassment", "hate_speech", "sexually_explicit");
		// Every probe asks about exactly one guideline; sending them all in one prompt is what the
		// model was explicitly not tuned for.
		assertThat(probes.get(0).prompt()).contains("No Dangerous Content").doesNotContain("No Harassment");
	}

	@Test
	void testShieldGemmaTextPromptCarriesTheTurnMarkersAndTheQuestion() {
		GuardNodeOptions options = options(GuardFamily.SHIELDGEMMA).setCategories(List.of("hate_speech"));
		String prompt = GuardDialect.of(GuardFamily.SHIELDGEMMA).textProbes(TEXT, options.effectiveCategories(), options).get(0).prompt();

		assertThat(prompt)
			.contains("You are a policy expert")
			.contains("<start_of_turn>")
			.contains("Human Question: " + TEXT)
			.contains("<end_of_turn>")
			.contains("\"No Hate Speech\"")
			.contains("Your answer must start with 'Yes' or 'No'.")
			.doesNotContain("${guideline}")
			.doesNotContain("${text}");
	}

	@Test
	void testShieldGemma2ImagePromptAsksAboutTheImageNotTheText() {
		GuardNodeOptions options = options(GuardFamily.SHIELDGEMMA_2);
		List<GuardProbe> probes = GuardDialect.of(GuardFamily.SHIELDGEMMA_2).imageProbes(options.effectiveCategories(), options);

		assertThat(probes).hasSize(3);
		assertThat(probes.get(0).prompt())
			.contains("Does the image violate the above principle?")
			.contains("\"No Sexually Explicit content\"")
			.doesNotContain("Human Question")
			.doesNotContain("${text}");
	}

	@Test
	void testShieldGemmaParsesYesIntoItsOwnCategory() {
		GuardNodeOptions options = options(GuardFamily.SHIELDGEMMA);
		GuardProbeResult result = GuardDialect.of(GuardFamily.SHIELDGEMMA).parse(
			GuardProbe.of("hate_speech", "p", 2),
			answer("Yes", probs("Yes", 0.8, "No", 0.2)),
			options);

		assertThat(result.score()).isCloseTo(0.8, within(1e-6));
		assertThat(result.hits()).hasSize(1);
		assertEquals("hate_speech", result.hits().get(0).nativeCode());
		assertEquals(GuardCategory.HATE, result.hits().get(0).canonical());
	}

	@Test
	void testShieldGemmaParsesNo() {
		GuardNodeOptions options = options(GuardFamily.SHIELDGEMMA);
		GuardProbeResult result = GuardDialect.of(GuardFamily.SHIELDGEMMA).parse(
			GuardProbe.of("harassment", "p", 2),
			answer("No", probs("No", 0.97, "Yes", 0.03)),
			options);

		assertThat(result.score()).isCloseTo(0.03, within(1e-6));
		assertThat(result.hits()).isEmpty();
	}

	// ----------------------------------------------------------------------------- Granite Guardian

	@Test
	void testGranitePromptCarriesTheRoleMarkersAndTheRiskDefinition() {
		GuardNodeOptions options = options(GuardFamily.GRANITE_GUARDIAN).setCategories(List.of("jailbreak"));
		List<GuardProbe> probes = GuardDialect.of(GuardFamily.GRANITE_GUARDIAN).textProbes(TEXT, options.effectiveCategories(), options);

		assertThat(probes).hasSize(1);
		assertThat(probes.get(0).prompt())
			.contains("<|start_of_role|>system<|end_of_role|>")
			.contains("<|start_of_role|>assistant<|end_of_role|>")
			.contains("<start_of_risk_definition>")
			.contains("circumvention of AI systems' built-in safeguards")
			.contains("User Message: " + TEXT)
			.contains("Your answer must be either 'Yes' or 'No'.")
			.doesNotContain("${guideline}");
	}

	@Test
	void testGraniteIssuesOneProbePerSelectedCriterion() {
		GuardNodeOptions options = options(GuardFamily.GRANITE_GUARDIAN);
		assertThat(GuardDialect.of(GuardFamily.GRANITE_GUARDIAN).textProbes(TEXT, options.effectiveCategories(), options))
			.hasSize(GuardTaxonomy.codes(GuardFamily.GRANITE_GUARDIAN).size());
	}

	@Test
	void testGraniteParsesTheThinkingModeScoreWrapper() {
		// Granite Guardian 3.3 wraps its answer when thinking mode is on. The prompt does not turn
		// it on, but a build that answers this way anyway must still be scored on 'yes', not on '<'.
		GuardNodeOptions options = options(GuardFamily.GRANITE_GUARDIAN);
		GuardCompletion completion = new GuardCompletion("<score>yes</score>", List.of(
			Map.of("<score>", 0.99),
			Map.of("yes", 0.91, "no", 0.09)));

		GuardProbeResult result = GuardDialect.of(GuardFamily.GRANITE_GUARDIAN).parse(GuardProbe.of("violence", "p", 2), completion, options);

		assertThat(result.score()).isCloseTo(0.91, within(1e-6));
		assertThat(result.scoreExact()).isTrue();
		assertEquals(GuardCategory.VIOLENT_CRIME, result.hits().get(0).canonical());
	}

	@Test
	void testGraniteRefusesImages() {
		GuardNodeOptions options = options(GuardFamily.GRANITE_GUARDIAN);
		assertThrows(UnsupportedOperationException.class,
			() -> GuardDialect.of(GuardFamily.GRANITE_GUARDIAN).imageProbes(options.effectiveCategories(), options));
	}

	// -------------------------------------------------------------------------------------- Shared

	@ParameterizedTest
	@EnumSource(GuardFamily.class)
	void testTheTemplateOverrideReplacesTheBuiltInPrompt(GuardFamily family) {
		GuardNodeOptions options = options(family).setPromptTemplate("ONLY THIS: ${text}");
		String prompt = GuardDialect.of(family).textProbes(TEXT, options.effectiveCategories(), options).get(0).prompt();

		assertThat(prompt).isEqualTo("ONLY THIS: " + TEXT);
	}

	@ParameterizedTest
	@EnumSource(GuardFamily.class)
	void testNoBackendLogprobsDegradesToAnInexactArgmax(GuardFamily family) {
		// A fabricated 0.87 would be worse than an honest 1.0: a threshold tuned against it would
		// be meaningless. The verdict says so rather than pretending.
		GuardNodeOptions options = options(family);
		String unsafeAnswer = family == GuardFamily.LLAMA_GUARD_3 || family == GuardFamily.LLAMA_GUARD_4 ? "unsafe\nS1" : "Yes";

		GuardProbeResult result = GuardDialect.of(family).parse(
			GuardProbe.of(GuardTaxonomy.codes(family).get(0), "p", 2),
			GuardCompletion.textOnly(unsafeAnswer),
			options);

		assertEquals(1d, result.score());
		assertThat(result.scoreExact()).isFalse();
		assertThat(result.hits()).isNotEmpty();
	}
}
