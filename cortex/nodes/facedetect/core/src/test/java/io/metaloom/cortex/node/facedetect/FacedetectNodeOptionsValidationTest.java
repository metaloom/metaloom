package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class FacedetectNodeOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		assertThat(options).isValid();
	}

	@Test
	public void testCustomVideoChopRateValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoChopRate(10);
		io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat(options)
			.isValid().hasVideoChopRate(10);
	}

	@Test
	public void testZeroVideoChopRateInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoChopRate(0);
		assertThat(options).isInvalid().hasError("videoChopRate must be positive");
	}

	@Test
	public void testNegativeVideoChopRateInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoChopRate(-1);
		assertThat(options).isInvalid().hasError("videoChopRate must be positive");
	}

	@Test
	public void testCustomVideoScaleSizeValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoScaleSize(320);
		io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat(options)
			.isValid().hasVideoScaleSize(320);
	}

	@Test
	public void testZeroVideoScaleSizeInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoScaleSize(0);
		assertThat(options).isInvalid().hasError("videoScaleSize must be positive");
	}

	@Test
	public void testNegativeVideoScaleSizeInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoScaleSize(-1);
		assertThat(options).isInvalid().hasError("videoScaleSize must be positive");
	}

	@Test
	public void testCustomFaceClusterMinimumValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setFaceClusterMinimum(3);
		io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat(options)
			.isValid().hasFaceClusterMinimum(3);
	}

	@Test
	public void testZeroFaceClusterMinimumInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setFaceClusterMinimum(0);
		assertThat(options).isInvalid().hasError("faceClusterMinimum must be positive");
	}

	@Test
	public void testCustomFaceClusterEPSValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setFaceClusterEPS(0.5f);
		io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat(options)
			.isValid().hasFaceClusterEPS(0.5f);
	}

	@Test
	public void testZeroFaceClusterEPSInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setFaceClusterEPS(0.0f);
		assertThat(options).isInvalid().hasError("faceClusterEPS must be positive");
	}

	@Test
	public void testNegativeFaceClusterEPSInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setFaceClusterEPS(-0.1f);
		assertThat(options).isInvalid().hasError("faceClusterEPS must be positive");
	}

	@Test
	public void testMinFaceHeightFactorValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setMinFaceHeightFactor(0.1f);
		io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat(options)
			.isValid().hasMinFaceHeightFactor(0.1f);
	}

	@Test
	public void testMinFaceHeightFactorZeroInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setMinFaceHeightFactor(0.0f);
		assertThat(options).isInvalid().hasError("minFaceHeightFactor must be between 0 and 1");
	}

	@Test
	public void testMinFaceHeightFactorNegativeInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setMinFaceHeightFactor(-0.1f);
		assertThat(options).isInvalid().hasError("minFaceHeightFactor must be between 0 and 1");
	}

	@Test
	public void testMinFaceHeightFactorGreaterThanOneInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setMinFaceHeightFactor(1.5f);
		assertThat(options).isInvalid().hasError("minFaceHeightFactor must be between 0 and 1");
	}

	@Test
	public void testEmptyInspirefacePackPathInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setInspirefacePackPath("");
		assertThat(options).isInvalid().hasError("inspirefacePackPath must not be empty");
	}

	@Test
	public void testNullInspirefacePackPathInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setInspirefacePackPath(null);
		assertThat(options).isInvalid().hasError("inspirefacePackPath must not be empty");
	}

	@Test
	public void testCustomInspirefacePackPathValid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setInspirefacePackPath("custom/path");
		io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat(options)
			.isValid().hasInspirefacePackPath("custom/path");
	}

	@Test
	public void testEmptyCapabilitiesInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setCapabilities(java.util.Set.of());
		assertThat(options).isInvalid().hasError("capabilities must not be empty");
	}

	@Test
	public void testNullCapabilitiesInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setCapabilities(null);
		assertThat(options).isInvalid().hasError("capabilities must not be empty");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative");
	}

	@Test
	public void testValidationResultDirect() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setVideoChopRate(0);
		
		ValidationResult result = options.validate();
		assertThat(result).isInvalid().hasErrorCount(1).hasError("videoChopRate must be positive");
		
		FacedetectNodeOptions validOptions = new FacedetectNodeOptions();
		ValidationResult validResult = validOptions.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}