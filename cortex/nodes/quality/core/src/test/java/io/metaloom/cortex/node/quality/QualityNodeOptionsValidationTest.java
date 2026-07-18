package io.metaloom.cortex.node.quality;

import static io.metaloom.cortex.node.quality.assertj.QualityNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class QualityNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		QualityNodeOptions options = new QualityNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testAllChecksEnabledValid() {
		QualityNodeOptions options = new QualityNodeOptions();
		options.setCheckBlurriness(true);
		options.setCheckResolution(true);
		options.setCheckVideoBitrate(true);
		options.setCheckAudioBitrate(true);
		assertThat(options)
			.isValid()
			.hasCheckBlurriness(true)
			.hasCheckResolution(true)
			.hasCheckVideoBitrate(true)
			.hasCheckAudioBitrate(true);
	}

	@Test
	public void testOnlyBlurrinessEnabledValid() {
		QualityNodeOptions options = new QualityNodeOptions();
		options.setCheckBlurriness(true);
		options.setCheckResolution(false);
		options.setCheckVideoBitrate(false);
		options.setCheckAudioBitrate(false);
		assertThat(options)
			.isValid()
			.hasCheckBlurriness(true)
			.hasCheckResolution(false)
			.hasCheckVideoBitrate(false)
			.hasCheckAudioBitrate(false);
	}

	@Test
	public void testNoChecksEnabledInvalid() {
		QualityNodeOptions options = new QualityNodeOptions();
		options.setCheckBlurriness(false);
		options.setCheckResolution(false);
		options.setCheckVideoBitrate(false);
		options.setCheckAudioBitrate(false);
		assertThat(options).isInvalid().hasErrorCount(1).hasError("At least one quality check");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		QualityNodeOptions options = new QualityNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		QualityNodeOptions options = new QualityNodeOptions();
		options.setCheckBlurriness(false);
		options.setCheckResolution(false);
		options.setCheckVideoBitrate(false);
		options.setCheckAudioBitrate(false);
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("At least one quality check");
		
		QualityNodeOptions validOptions = new QualityNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}