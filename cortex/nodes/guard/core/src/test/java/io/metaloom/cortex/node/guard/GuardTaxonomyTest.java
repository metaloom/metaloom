package io.metaloom.cortex.node.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.metaloom.cortex.node.guard.GuardTaxonomy.NativeCategory;

/**
 * The mapping tables are what makes one node cover three families, and they are the part most likely
 * to rot: a family gains a hazard code, an enum constant is added without a table, a canonical bucket
 * is renamed. None of that shows up as a compile error, so it is pinned here.
 */
class GuardTaxonomyTest {

	@ParameterizedTest
	@EnumSource(GuardFamily.class)
	void testEveryFamilyHasANonEmptyTable(GuardFamily family) {
		// GuardTaxonomy.table() throws for an unregistered family, so adding an enum constant
		// without a table fails here rather than at the first asset.
		assertThat(GuardTaxonomy.codes(family)).isNotEmpty();
		assertThat(GuardTaxonomy.categories(family)).hasSameSizeAs(GuardTaxonomy.codes(family));
	}

	@ParameterizedTest
	@EnumSource(GuardFamily.class)
	void testEveryCodeResolvesToItselfWithACanonicalBucket(GuardFamily family) {
		for (String code : GuardTaxonomy.codes(family)) {
			NativeCategory category = GuardTaxonomy.resolve(family, code);
			assertEquals(code, category.code());
			assertThat(category.canonical()).isNotNull();
			assertThat(category.label()).isNotBlank();
			assertThat(GuardTaxonomy.isKnown(family, code)).isTrue();
		}
	}

	@Test
	void testCodesAreInTaxonomyOrder() {
		// The order is what the rendered Llama Guard category block and the editor's picker use, so
		// it is behaviour rather than presentation.
		assertThat(GuardTaxonomy.codes(GuardFamily.LLAMA_GUARD_3))
			.containsExactly("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12", "S13", "S14");
	}

	@Test
	void testLlamaGuard4DroppedS14() {
		assertThat(GuardTaxonomy.codes(GuardFamily.LLAMA_GUARD_4)).hasSize(13).doesNotContain("S14");
		assertThat(GuardTaxonomy.isKnown(GuardFamily.LLAMA_GUARD_4, "S14")).isFalse();

		// Everything below it kept its number - the two tables must not have drifted apart.
		for (String code : GuardTaxonomy.codes(GuardFamily.LLAMA_GUARD_4)) {
			assertEquals(GuardTaxonomy.resolve(GuardFamily.LLAMA_GUARD_3, code).canonical(),
				GuardTaxonomy.resolve(GuardFamily.LLAMA_GUARD_4, code).canonical(),
				code + " must mean the same thing in both Llama Guard versions");
		}
	}

	@Test
	void testTheThreeFamiliesAgreeOnSexualContent() {
		// The whole point of the canonical vocabulary: a pipeline routing on SEXUAL_CONTENT keeps
		// working when the operator swaps the model.
		assertEquals(GuardCategory.SEXUAL_CONTENT, GuardTaxonomy.resolve(GuardFamily.LLAMA_GUARD_3, "S12").canonical());
		assertEquals(GuardCategory.SEXUAL_CONTENT, GuardTaxonomy.resolve(GuardFamily.SHIELDGEMMA, "sexually_explicit").canonical());
		assertEquals(GuardCategory.SEXUAL_CONTENT, GuardTaxonomy.resolve(GuardFamily.SHIELDGEMMA_2, "sexually_explicit").canonical());
		assertEquals(GuardCategory.SEXUAL_CONTENT, GuardTaxonomy.resolve(GuardFamily.GRANITE_GUARDIAN, "sexual_content").canonical());
	}

	@Test
	void testHateIsReachedFromThreeDifferentNativeNames() {
		assertEquals(GuardCategory.HATE, GuardTaxonomy.resolve(GuardFamily.LLAMA_GUARD_3, "S10").canonical());
		assertEquals(GuardCategory.HATE, GuardTaxonomy.resolve(GuardFamily.SHIELDGEMMA, "hate_speech").canonical());
		assertEquals(GuardCategory.HATE, GuardTaxonomy.resolve(GuardFamily.GRANITE_GUARDIAN, "social_bias").canonical());
	}

	@Test
	void testUnknownCodeIsToleratedRatherThanThrown() {
		// A model revision that invents S15 must not turn every flagged item into a node failure.
		NativeCategory unknown = GuardTaxonomy.resolve(GuardFamily.LLAMA_GUARD_3, "S15");
		assertEquals("S15", unknown.code());
		assertEquals(GuardCategory.OTHER, unknown.canonical());
		assertThat(GuardTaxonomy.isKnown(GuardFamily.LLAMA_GUARD_3, "S15")).isFalse();
	}

	@Test
	void testGraniteCarriesRisksTheOthersHaveNoWordFor() {
		assertEquals(GuardCategory.JAILBREAK, GuardTaxonomy.resolve(GuardFamily.GRANITE_GUARDIAN, "jailbreak").canonical());
		assertEquals(GuardCategory.PROFANITY, GuardTaxonomy.resolve(GuardFamily.GRANITE_GUARDIAN, "profanity").canonical());
		assertEquals(GuardCategory.UNETHICAL_BEHAVIOUR, GuardTaxonomy.resolve(GuardFamily.GRANITE_GUARDIAN, "unethical_behavior").canonical());
	}

	@Test
	void testRagAndAgenticCriteriaAreDeliberatelyAbsent() {
		// They judge an answer against a source document, which this node has no input for.
		// Listing them would advertise a check the node cannot actually perform.
		List<String> codes = GuardTaxonomy.codes(GuardFamily.GRANITE_GUARDIAN);
		assertThat(codes).doesNotContain("groundedness", "context_relevance", "answer_relevance", "function_call");
	}

	@Test
	void testShieldGemma2HasTheImagePoliciesNotTheTextOnes() {
		assertThat(GuardTaxonomy.codes(GuardFamily.SHIELDGEMMA_2)).containsExactly("sexually_explicit", "violence_gore", "dangerous_content");
		assertThat(GuardTaxonomy.codes(GuardFamily.SHIELDGEMMA)).contains("harassment", "hate_speech");
		assertThat(GuardTaxonomy.isKnown(GuardFamily.SHIELDGEMMA_2, "harassment")).isFalse();
	}
}
