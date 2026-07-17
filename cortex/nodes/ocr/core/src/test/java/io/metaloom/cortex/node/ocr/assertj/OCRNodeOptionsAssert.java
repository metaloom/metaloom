package io.metaloom.cortex.node.ocr.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.ocr.OCRNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link OCRNodeOptions}.
 */
public class OCRNodeOptionsAssert extends CortexNodeOptionsAssert {

	public OCRNodeOptionsAssert(OCRNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as OCRNodeOptions.
	 */
	private OCRNodeOptions ocrOptions() {
		return (OCRNodeOptions) actual;
	}

	/**
	 * Assert that the tessDataPath is set to the expected value.
	 */
	public OCRNodeOptionsAssert hasTessDataPath(String expectedPath) {
		isNotNull();
		if (!expectedPath.equals(ocrOptions().getTessDataPath())) {
			failWithMessage("Expected tessDataPath to be '%s' but was '%s'", expectedPath, ocrOptions().getTessDataPath());
		}
		return this;
	}

	/**
	 * Assert that the tessDataPath is not empty.
	 */
	public OCRNodeOptionsAssert hasTessDataPath() {
		isNotNull();
		if (ocrOptions().getTessDataPath() == null || ocrOptions().getTessDataPath().isBlank()) {
			failWithMessage("Expected tessDataPath to be set but it was empty");
		}
		return this;
	}

	/**
	 * Assert that the language is set to the expected value.
	 */
	public OCRNodeOptionsAssert hasLanguage(String expectedLanguage) {
		isNotNull();
		if (!expectedLanguage.equals(ocrOptions().getLanguage())) {
			failWithMessage("Expected language to be '%s' but was '%s'", expectedLanguage, ocrOptions().getLanguage());
		}
		return this;
	}
}