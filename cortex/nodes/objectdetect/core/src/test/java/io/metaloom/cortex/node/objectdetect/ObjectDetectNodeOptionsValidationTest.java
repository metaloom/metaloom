package io.metaloom.cortex.node.objectdetect;

import static io.metaloom.cortex.node.objectdetect.assertj.ObjectDetectNodeAssertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The {@code validate()} contract: the defaults are usable as shipped, and every field that can be
 * set out of range says so by name.
 */
class ObjectDetectNodeOptionsValidationTest {

	@Test
	void testDefaultsAreValid() {
		assertThat(new ObjectDetectNodeOptions())
			.isValid()
			.hasModelPath("models/yolo/YOLOv11n_voc.onnx")
			.hasLabelsPath("models/yolo/voc.names")
			.hasUseGpu(true)
			.hasMinConfidence(0.5f)
			.hasVideoChopRate(25)
			.hasVideoScaleSize(1024)
			.hasMaxDetections(500)
			.hasClassFilter(Set.of());
	}

	@Test
	void testAnEmptyModelPathIsRejected() {
		assertThat(new ObjectDetectNodeOptions().setModelPath("  ")).isInvalid().hasError("modelPath must not be empty");
		assertThat(new ObjectDetectNodeOptions().setModelPath(null)).isInvalid().hasError("modelPath must not be empty");
	}

	@Test
	void testAnEmptyLabelsPathIsRejected() {
		assertThat(new ObjectDetectNodeOptions().setLabelsPath("")).isInvalid().hasError("labelsPath must not be empty");
		assertThat(new ObjectDetectNodeOptions().setLabelsPath(null)).isInvalid().hasError("labelsPath must not be empty");
	}

	@Test
	void testConfidenceBelowTheNativeFloorIsRejected() {
		// Not pedantry: yolib.cpp calls YOLODetector::detect with its own confThreshold of 0.4 and
		// exposes no way to lower it, so anything under that filters nothing - the detections never
		// arrive in the first place. Accepting 0.2 would advertise a knob that does not work.
		assertThat(new ObjectDetectNodeOptions().setMinConfidence(0.2f)).isInvalid().hasError("minConfidence must be between");
		assertThat(new ObjectDetectNodeOptions().setMinConfidence(ObjectDetectNodeOptions.NATIVE_CONFIDENCE_FLOOR)).isValid();
	}

	@Test
	void testConfidenceAboveOneIsRejected() {
		assertThat(new ObjectDetectNodeOptions().setMinConfidence(1.5f)).isInvalid().hasError("minConfidence must be between");
		assertThat(new ObjectDetectNodeOptions().setMinConfidence(1.0f)).isValid();
	}

	@Test
	void testNonPositiveChopRateIsRejected() {
		assertThat(new ObjectDetectNodeOptions().setVideoChopRate(0)).isInvalid().hasError("videoChopRate must be positive");
		assertThat(new ObjectDetectNodeOptions().setVideoChopRate(-5)).isInvalid().hasError("videoChopRate must be positive");
		assertThat(new ObjectDetectNodeOptions().setVideoChopRate(1)).isValid();
	}

	@Test
	void testNonPositiveScaleSizeIsRejected() {
		assertThat(new ObjectDetectNodeOptions().setVideoScaleSize(0)).isInvalid().hasError("videoScaleSize must be positive");
		assertThat(new ObjectDetectNodeOptions().setVideoScaleSize(-1)).isInvalid().hasError("videoScaleSize must be positive");
	}

	@Test
	void testNonPositiveMaxDetectionsIsRejected() {
		assertThat(new ObjectDetectNodeOptions().setMaxDetections(0)).isInvalid().hasError("maxDetections must be positive");
		assertThat(new ObjectDetectNodeOptions().setMaxDetections(-1)).isInvalid().hasError("maxDetections must be positive");
	}

	@Test
	void testANullClassFilterMeansKeepEverything() {
		// Null is how a YAML key written with no value arrives. "Keep everything" is the only sane
		// reading, and it must not become an NPE in the middle of a scan.
		assertThat(new ObjectDetectNodeOptions().setClassFilter(null)).isValid().hasClassFilter(Set.of());
	}

	@Test
	void testTheNormalizedFilterIsLowerCasedAndTrimmed() {
		ObjectDetectNodeOptions options = new ObjectDetectNodeOptions().setClassFilter(Set.of(" Person ", "CAR", "  "));
		assertThat(options).isValid();
		// The blank entry is dropped rather than turned into a class nothing matches.
		assertThat(options.normalizedClassFilter()).containsExactlyInAnyOrder("person", "car");
	}
}
