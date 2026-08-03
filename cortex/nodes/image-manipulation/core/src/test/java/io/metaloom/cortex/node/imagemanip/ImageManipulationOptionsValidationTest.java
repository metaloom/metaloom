package io.metaloom.cortex.node.imagemanip;

import static io.metaloom.cortex.node.imagemanip.assertj.ImageManipulationNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@code validate()} contract.
 *
 * <p>
 * {@code RegistryNodeRegistrar} runs this at node construction, so everything caught here surfaces when the pipeline starts rather than on every image
 * the node touches. That is the difference between a typo being a startup error and being a silent no-op across a library.
 * </p>
 */
class ImageManipulationOptionsValidationTest {

	private static ImageManipulationNodeOptions options() {
		return new ImageManipulationNodeOptions();
	}

	@Test
	void testTheDefaultsAreValidExceptForTheAspectTheyCannotGuess() {
		// The default chain includes ASPECT, and there is no sensible default target ratio - so the
		// out-of-the-box options deliberately require the author to say what they want.
		assertThat(options()).isInvalidBecauseOf("targetAspect must be set");
		assertThat(options().setTargetAspect("16:9")).isValid();
	}

	@Test
	void testTheOperationChainParses() {
		assertThat(options().setOperations("AUTOROTATE,ASPECT,RESIZE").setTargetAspect("16:9"))
			.hasOperations(Op.AUTOROTATE, Op.ASPECT, Op.RESIZE);
		assertThat(options().setOperations(" autorotate , crop ").setTargetAspect(""))
			.hasOperations(Op.AUTOROTATE, Op.CROP)
			.isValid();
		assertThat(options().setOperations("SUBJECT-CROP").setTargetAspect(""))
			.hasOperations(Op.SUBJECT_CROP);
	}

	@Test
	void testAnUnknownOperationIsRejected() {
		assertThat(options().setOperations("AUTOROTATE,SHARPEN")).isInvalidBecauseOf("Not an operation: SHARPEN");
	}

	@Test
	void testAnEmptyChainIsRejected() {
		assertThat(options().setOperations("")).isInvalidBecauseOf("operations must name at least one");
		assertThat(options().setOperations("  ,  ")).isInvalidBecauseOf("operations must name at least one");
	}

	@Test
	void testARepeatedOperationIsRejected() {
		assertThat(options().setOperations("CROP,CROP").setTargetAspect("")).isInvalidBecauseOf("lists CROP more than once");
	}

	@Test
	void testAutorotateMustComeFirst() {
		// 🔴 Not a style rule: every later operation, and every detection box, is measured against the
		// frame autorotation rewrites.
		assertThat(options().setOperations("CROP,AUTOROTATE").setTargetAspect(""))
			.isInvalidBecauseOf("AUTOROTATE must be the first operation");
		assertThat(options().setOperations("AUTOROTATE,CROP").setTargetAspect("")).isValid();
		assertThat(options().setOperations("CROP,RESIZE").setTargetAspect("")).isValid();
	}

	@Test
	void testCropFractionsMustBeWithinTheFrame() {
		assertThat(options().setOperations("CROP").setTargetAspect("").setCropX(-0.1d)).isInvalidBecauseOf("cropX must be within");
		assertThat(options().setOperations("CROP").setTargetAspect("").setCropY(1.5d)).isInvalidBecauseOf("cropY must be within");
		assertThat(options().setOperations("CROP").setTargetAspect("").setCropWidth(0d)).isInvalidBecauseOf("cropWidth must be within");
		assertThat(options().setOperations("CROP").setTargetAspect("").setCropHeight(1.2d)).isInvalidBecauseOf("cropHeight must be within");
	}

	@Test
	void testACropOriginOnTheFrameEdgeIsRejectedOnlyWhenCropIsUsed() {
		assertThat(options().setOperations("CROP").setTargetAspect("").setCropX(1.0d))
			.isInvalidBecauseOf("cropX must leave at least one column");
		// The same value with no CROP step is harmless - the field is never read.
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setCropX(1.0d)).isValid();
	}

	@Test
	void testSubjectKnobsAreBounded() {
		assertThat(options().setOperations("SUBJECT_CROP").setTargetAspect("").setMinConfidence(1.5d))
			.isInvalidBecauseOf("minConfidence must be within");
		assertThat(options().setOperations("SUBJECT_CROP").setTargetAspect("").setSubjectPadding(-0.1d))
			.isInvalidBecauseOf("subjectPadding must not be negative");
	}

	@Test
	void testAMalformedAspectIsRejected() {
		assertThat(options().setOperations("ASPECT").setTargetAspect("16/9")).isInvalidBecauseOf("targetAspect must be a W:H ratio");
		assertThat(options().setOperations("ASPECT").setTargetAspect("wide")).isInvalidBecauseOf("targetAspect must be a W:H ratio");
		assertThat(options().setOperations("ASPECT").setTargetAspect("1:1")).isValid();
	}

	@Test
	void testAnAspectStepWithNoRatioIsRejectedRatherThanSilentlyDoingNothing() {
		assertThat(options().setOperations("ASPECT").setTargetAspect("")).isInvalidBecauseOf("targetAspect must be set");
	}

	@Test
	void testColoursMustBeSixHexDigits() {
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setPadColor("#fff")).isInvalidBecauseOf("padColor must be a #RRGGBB");
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setBackgroundColor("white"))
			.isInvalidBecauseOf("backgroundColor must be a #RRGGBB");
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setPadColor("102030")).isValid();
	}

	@Test
	void testBlurKnobsAreBounded() {
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setBlurRadius(0)).isInvalidBecauseOf("blurRadius must be at least 1");
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setBlurZoom(0.9d)).isInvalidBecauseOf("blurZoom must be at least 1.0");
	}

	@Test
	void testResizeKnobsAgreeWithEachOther() {
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setMaxLongEdge(-1)).isInvalidBecauseOf("maxLongEdge must not be negative");
		// Upscaling towards nothing is always a mistake, and a silent one.
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setAllowUpscale(true))
			.isInvalidBecauseOf("allowUpscale needs a maxLongEdge");
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setAllowUpscale(true).setMaxLongEdge(2000))
			.isValid()
			.hasMaxLongEdge(2000);
	}

	@Test
	void testJpegQualityIsBounded() {
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setJpegQuality(0d)).isInvalidBecauseOf("jpegQuality must be within");
		assertThat(options().setOperations("RESIZE").setTargetAspect("").setJpegQuality(1.1d)).isInvalidBecauseOf("jpegQuality must be within");
	}

	@Test
	void testTheInheritedTimeoutIsStillChecked() {
		assertThat(options().setOperations("RESIZE").setTargetAspect("")).isValid();
		ImageManipulationNodeOptions negative = options().setOperations("RESIZE").setTargetAspect("");
		negative.setTimeoutMs(-1);
		assertThat(negative).isInvalidBecauseOf("timeoutMs must be non-negative");
	}

	@Test
	void testTheVerticalVideoPresetValidates() {
		assertThat(options()
			.setOperations("AUTOROTATE,ASPECT")
			.setTargetAspect("16:9")
			.setAspectMode(AspectMode.PAD)
			.setPadFill(PadFill.BLUR))
				.isValid()
				.hasOperations(Op.AUTOROTATE, Op.ASPECT)
				.hasTargetAspect("16:9");
	}
}
