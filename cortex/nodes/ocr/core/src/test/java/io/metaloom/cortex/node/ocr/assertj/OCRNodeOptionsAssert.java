package io.metaloom.cortex.node.ocr.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.ocr.OCRNodeOptions;

/**
 * AssertJ assertions for {@link OCRNodeOptions}.
 */
public class OCRNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<OCRNodeOptionsAssert, OCRNodeOptions> {

	public OCRNodeOptionsAssert(OCRNodeOptions actual) {
		super(actual, OCRNodeOptionsAssert.class);
	}

	/**
	 * Assert that the tessDataPath is set to the expected value.
	 */
	public OCRNodeOptionsAssert hasTessDataPath(String expectedPath) {
		isNotNull();
		if (!expectedPath.equals(actual.getTessDataPath())) {
			failWithMessage("Expected tessDataPath to be '%s' but was '%s'", expectedPath, actual.getTessDataPath());
		}
		return this;
	}

	/**
	 * Assert that the tessDataPath is not empty.
	 */
	public OCRNodeOptionsAssert hasTessDataPath() {
		isNotNull();
		if (actual.getTessDataPath() == null || actual.getTessDataPath().isBlank()) {
			failWithMessage("Expected tessDataPath to be set but it was empty");
		}
		return this;
	}

	/**
	 * Assert that the language is set to the expected value.
	 */
	public OCRNodeOptionsAssert hasLanguage(String expectedLanguage) {
		isNotNull();
		if (!expectedLanguage.equals(actual.getLanguage())) {
			failWithMessage("Expected language to be '%s' but was '%s'", expectedLanguage, actual.getLanguage());
		}
		return this;
	}
}
