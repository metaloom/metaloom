package io.metaloom.cortex.node.guard;

import static io.metaloom.cortex.node.guard.assertj.GuardNodeAssertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The {@code validate()} contract: the defaults are usable as they stand, and every way of breaking
 * a field is reported rather than discovered at run time on a worker.
 */
class GuardOptionsValidationTest {

	@Test
	void testDefaultsAreValid() {
		assertThat(new GuardNodeOptions())
			.isValid()
			.hasFamily(GuardFamily.LLAMA_GUARD_3)
			.hasModel("meta-llama/Llama-Guard-3-8B")
			.hasOpenaiUrl("http://127.0.0.1:8080/v1")
			.hasThreshold(0.5)
			.hasMaxChars(8000)
			.hasMaxImageDim(1024);
	}

	@Test
	void testEmptyOpenaiUrlIsInvalid() {
		GuardNodeOptions options = new GuardNodeOptions();
		options.setOpenaiUrl("");

		assertThat(options).isInvalid().hasError("openaiUrl must not be empty").hasErrorCount(1);
	}

	@Test
	void testBlankModelIsInvalid() {
		assertThat(new GuardNodeOptions().setModel("  ")).isInvalid().hasError("model must not be empty").hasErrorCount(1);
	}

	@Test
	void testThresholdOutsideTheUnitIntervalIsInvalid() {
		assertThat(new GuardNodeOptions().setThreshold(1.5))
			.isInvalid().hasError("threshold must be between 0.0 and 1.0, got 1.5").hasErrorCount(1);
		assertThat(new GuardNodeOptions().setThreshold(-0.1))
			.isInvalid().hasError("threshold must be between 0.0 and 1.0, got -0.1").hasErrorCount(1);
	}

	@Test
	void testTheThresholdBoundsThemselvesAreValid() {
		// 0.0 flags everything and 1.0 flags nothing; both are legitimate, if blunt, configurations.
		assertThat(new GuardNodeOptions().setThreshold(0d)).isValid();
		assertThat(new GuardNodeOptions().setThreshold(1d)).isValid();
	}

	@Test
	void testNonPositiveMaxCharsIsInvalid() {
		assertThat(new GuardNodeOptions().setMaxChars(0)).isInvalid().hasError("maxChars must be at least 1, got 0").hasErrorCount(1);
	}

	@Test
	void testTooSmallMaxImageDimIsInvalid() {
		assertThat(new GuardNodeOptions().setMaxImageDim(32))
			.isInvalid().hasError("maxImageDim must be at least 64, got 32").hasErrorCount(1);
	}

	@Test
	void testACodeTheFamilyDoesNotKnowIsRejected() {
		// A typo'd code would silently drop a category the operator believes is being checked -
		// the worst way for a content guard to fail, so it is caught at configuration time.
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA).setCategories(List.of("hate_speach")))
			.isInvalid()
			.hasErrorCount(1);
	}

	@Test
	void testACodeFromTheWrongFamilyIsRejected() {
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA).setCategories(List.of("S12"))).isInvalid();
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_3).setCategories(List.of("hate_speech"))).isInvalid();
		// S14 exists in Llama Guard 3 and was dropped in 4, which is the mistake most likely to be
		// made by copying a working configuration onto a new model.
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_3).setCategories(List.of("S14"))).isValid();
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_4).setCategories(List.of("S14"))).isInvalid();
	}

	@Test
	void testEmptyCategoriesMeansEveryCategory() {
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.SHIELDGEMMA))
			.isValid()
			.hasEffectiveCategories(List.of("dangerous_content", "harassment", "hate_speech", "sexually_explicit"));
	}

	@Test
	void testSelectedCategoriesAreReorderedIntoTaxonomyOrder() {
		// The rendered prompt must not change with the order the author happened to tick the boxes,
		// or the result cache would miss on a configuration that is semantically identical.
		assertThat(new GuardNodeOptions().setFamily(GuardFamily.LLAMA_GUARD_3).setCategories(List.of("S12", "S1")))
			.isValid()
			.hasEffectiveCategories(List.of("S1", "S12"));
	}

	@Test
	void testTheCategoryPickerOffersEveryFamilysCodes() throws Exception {
		// The descriptor's allowed values are static while the real vocabulary depends on the
		// selected family, so the annotation carries the union. Nothing else notices when a family
		// gains a code and the picker does not - the editor just quietly stops offering it.
		String[] offered = GuardNodeOptions.class.getDeclaredField("categories")
			.getAnnotation(io.metaloom.cortex.api.node.spec.ParamDoc.class).values();

		for (GuardFamily family : GuardFamily.values()) {
			org.assertj.core.api.Assertions.assertThat(offered)
				.as("the category picker must offer every code %s knows", family)
				.contains(GuardTaxonomy.codes(family).toArray(String[]::new));
		}
	}

	@Test
	void testNullCategoriesIsTreatedAsUnset() {
		assertThat(new GuardNodeOptions().setCategories(null))
			.isValid()
			.hasEffectiveCategories(GuardTaxonomy.codes(GuardFamily.LLAMA_GUARD_3));
	}
}
