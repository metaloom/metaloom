package io.metaloom.cortex.node.videogen;

import static io.metaloom.cortex.node.videogen.assertj.VideoGenNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class VideoGenOptionsValidationTest {

	@Test
	public void testDefaultOptionsValidAfterPrompt() {
		// A prompt is required; the rest of the defaults are valid.
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("a glowing loom");
		assertThat(options).isValid()
			.hasMode(VideoGenMode.GENERATE)
			.hasPrompt("a glowing loom")
			.hasHost("localhost")
			.hasPort(9220)
			.hasWidth(768)
			.hasHeight(512)
			.hasNumFrames(49)
			.hasFps(24)
			.hasSteps(40);
	}

	@Test
	public void testCustomOptionsValid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions()
			.setMode(VideoGenMode.ANIMATE)
			.setPrompt("cyberpunk city")
			.setHost("videogen.internal")
			.setPort(9300)
			.setWidth(512)
			.setHeight(320)
			.setNumFrames(33)
			.setFps(30)
			.setGuidance(3.5)
			.setSteps(20);
		assertThat(options).isValid()
			.hasMode(VideoGenMode.ANIMATE)
			.hasPrompt("cyberpunk city")
			.hasHost("videogen.internal")
			.hasPort(9300)
			.hasWidth(512)
			.hasHeight(320)
			.hasNumFrames(33)
			.hasFps(30)
			.hasGuidance(3.5)
			.hasSteps(20);
	}

	@Test
	public void testEmptyPromptInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions();
		assertThat(options).isInvalid().hasError("prompt must not be empty");
	}

	@Test
	public void testEmptyHostInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x").setHost("");
		assertThat(options).isInvalid().hasError("host must not be empty");
	}

	@Test
	public void testNonPositivePortInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x").setPort(0);
		assertThat(options).isInvalid().hasError("port must be positive, got 0");
	}

	@Test
	public void testNonPositiveWidthInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x").setWidth(0);
		assertThat(options).isInvalid().hasError("width must be positive, got 0");
	}

	@Test
	public void testNonPositiveNumFramesInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x").setNumFrames(0);
		assertThat(options).isInvalid().hasError("numFrames must be positive, got 0");
	}

	@Test
	public void testNonPositiveStepsInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x").setSteps(-1);
		assertThat(options).isInvalid().hasError("steps must be positive, got -1");
	}

	@Test
	public void testNegativeGuidanceInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x").setGuidance(-1.0);
		assertThat(options).isInvalid().hasError("guidance must be non-negative, got -1.0");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		VideoGenNodeOptions options = new VideoGenNodeOptions().setPrompt("x");
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative, got -1");
	}

	@Test
	public void testValidationResultDirect() {
		VideoGenNodeOptions valid = new VideoGenNodeOptions().setPrompt("x");
		ValidationResult validResult = valid.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}
