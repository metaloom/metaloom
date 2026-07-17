package io.metaloom.cortex.node.ocr.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.ocr.OCRNodeOptions;

/**
 * Entry point for OCR node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.ocr.assertj.OCRNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasTessDataPath("/usr/share/tessdata").hasLanguage("eng");
 * </pre>
 */
public class OCRNodeAssertions extends Assertions {

	public static OCRNodeOptionsAssert assertThat(OCRNodeOptions actual) {
		return new OCRNodeOptionsAssert(actual);
	}
}