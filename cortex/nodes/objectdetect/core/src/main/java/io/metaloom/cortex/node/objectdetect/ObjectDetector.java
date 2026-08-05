package io.metaloom.cortex.node.objectdetect;

import java.awt.image.BufferedImage;
import java.util.List;

import io.metaloom.opencv.core.Mat;

/**
 * The detection engine, behind an interface.
 *
 * <p>
 * {@code YoloLib} is a static facade over a native global — it cannot be constructed, mocked or
 * pointed at a second model, and touching it loads {@code libyolib.so} plus an ONNX Runtime. That is
 * fine for the one implementation that has to talk to it and unacceptable for everything else, so
 * the node depends on this instead. Every test in this module stubs it; only
 * {@link YoloObjectDetector} knows the native library exists.
 * </p>
 *
 * <p>
 * This is the same seam {@code facedetect} gets from {@code InspireFacedetector} — the reason its
 * node and integration tests run without a model pack.
 * </p>
 */
public interface ObjectDetector {

	/**
	 * Detect objects in one frame.
	 *
	 * <p>
	 * Boxes come back in the coordinate space of the {@code Mat} that was passed in. A caller that
	 * downscaled the frame first is responsible for scaling them back.
	 * </p>
	 *
	 * @param frame the image to search; never modified by the detector
	 * @return the detections, never null
	 */
	List<ObjectDetection> detect(Mat frame);

	/**
	 * Detect objects in one decoded image.
	 *
	 * <p>
	 * The overload exists so the image path never has to hold a {@code Mat}. Allocating one means
	 * loading the OpenCV natives, and the whole point of this interface is that everything above it —
	 * the node, and every test of it — works without them. The video path has a {@code Mat} already,
	 * so it uses the other one.
	 * </p>
	 *
	 * @param image the image to search; never modified by the detector
	 * @return the detections, never null
	 */
	List<ObjectDetection> detect(BufferedImage image);

	/**
	 * The class names this detector can produce, in class-id order.
	 *
	 * <p>
	 * Exposed so a configured class filter can be checked against the model that is actually loaded,
	 * rather than silently matching nothing at runtime.
	 * </p>
	 *
	 * @return the labels, empty when the engine has not been initialized yet
	 */
	List<String> labels();
}
