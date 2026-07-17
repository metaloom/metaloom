package io.metaloom.cortex.node.ocr;

import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class OCRNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		OCRNodeOptions options = new OCRNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testCustomTessDataPathValid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setTessDataPath("/custom/tessdata");
		io.metaloom.cortex.node.ocr.assertj.OCRNodeAssertions.assertThat(options)
			.isValid().hasTessDataPath("/custom/tessdata");
	}

	@Test
	public void testEmptyTessDataPathInvalid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setTessDataPath("");
		assertThat(options).isInvalid().hasErrorCount(1).hasError("tessDataPath must not be empty");
	}

	@Test
	public void testNullTessDataPathInvalid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setTessDataPath(null);
		assertThat(options).isInvalid().hasError("tessDataPath must not be empty");
	}

	@Test
	public void testCustomLanguageValid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setLanguage("deu");
		io.metaloom.cortex.node.ocr.assertj.OCRNodeAssertions.assertThat(options)
			.isValid().hasLanguage("deu");
	}

	@Test
	public void testEmptyLanguageInvalid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setLanguage("");
		assertThat(options).isInvalid().hasError("language must not be empty");
	}

	@Test
	public void testNullLanguageInvalid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setLanguage(null);
		assertThat(options).isInvalid().hasError("language must not be empty");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setTessDataPath("");
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("tessDataPath must not be empty");
		
		OCRNodeOptions validOptions = new OCRNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}