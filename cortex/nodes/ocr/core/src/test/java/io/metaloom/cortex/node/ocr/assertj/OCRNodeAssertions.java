package io.metaloom.cortex.node.ocr.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.ocr.OCRNodeOptions;

/**
 * Entry point for OCR node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all an OCR
 * test needs — it exposes the OCR assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.ocr.assertj.OCRNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasTessDataPath("/usr/share/tessdata").hasLanguage("eng");
 * </pre>
 */
public class OCRNodeAssertions extends NodeAssertions {

	public static OCRNodeOptionsAssert assertThat(OCRNodeOptions actual) {
		return new OCRNodeOptionsAssert(actual);
	}
}
