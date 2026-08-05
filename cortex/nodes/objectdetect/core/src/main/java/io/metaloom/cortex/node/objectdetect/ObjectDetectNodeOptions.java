package io.metaloom.cortex.node.objectdetect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options of the {@code objectdetect} node.
 *
 * <p>
 * The key is the kind, unlike {@code facedetect}/{@code facedetection} where the two drifted apart
 * and now cannot be reconciled without breaking existing worker configuration.
 * </p>
 */
public class ObjectDetectNodeOptions extends AbstractNodeOptions<ObjectDetectNodeOptions> {

	public static final String KEY = "objectdetect";

	/**
	 * The lowest confidence the native detector can report.
	 *
	 * <p>
	 * {@code YOLODetector} applies {@code confThreshold = 0.4f} internally and {@code yolib.cpp} does
	 * not expose it, so anything below this filters nothing — the detections simply never arrive.
	 * Validation rejects it rather than letting an author configure a threshold that silently does
	 * nothing.
	 * </p>
	 */
	public static final float NATIVE_CONFIDENCE_FLOOR = 0.4f;

	private static final String DEFAULT_MODEL_PATH = "models/yolo/YOLOv11n_voc.onnx";

	private static final String DEFAULT_LABELS_PATH = "models/yolo/voc.names";

	@Override
	protected ObjectDetectNodeOptions self() {
		return this;
	}

	/**
	 * Path to the ONNX model, relative to the worker's working directory.
	 */
	@ParamDoc(label = "Model Path", description = "ONNX detection model, e.g. YOLOv11n_voc.onnx")
	private String modelPath = DEFAULT_MODEL_PATH;

	/**
	 * Path to the newline-delimited class-name file that goes with the model.
	 */
	@ParamDoc(label = "Labels Path", description = "Class names for the model, one per line (voc.names, coco.names)")
	private String labelsPath = DEFAULT_LABELS_PATH;

	/**
	 * Whether to run inference on the GPU.
	 *
	 * <p>
	 * Defaults on because this node exists for video, and video object detection is per-frame
	 * inference. There is no auto-detection behind this: with no usable CUDA execution provider ONNX
	 * Runtime falls back to CPU on its own, so the worst case is slow rather than broken.
	 * </p>
	 */
	@ParamDoc(label = "Use GPU", description = "Run inference on the GPU when a CUDA execution provider is available")
	private boolean useGpu = true;

	/**
	 * Directory holding {@code libonnxruntime.so.1}.
	 *
	 * <p>
	 * Operational rather than authorial — where a worker keeps a shared library is not something a
	 * pipeline author decides — so it is hidden from the contract. Left empty, yolo4j hunts for an
	 * {@code onnxruntime-*} directory above the model, which only works inside its own checkout.
	 * </p>
	 */
	@ParamDoc(hidden = true)
	private String onnxRuntimeLibPath;

	/**
	 * Discard detections the model is less sure about than this.
	 */
	@ParamDoc(label = "Min Confidence", description = "Discard detections scoring below this; the engine never reports below 0.4",
		min = "0.4", max = "1.0", step = "0.05")
	private float minConfidence = 0.5f;

	/**
	 * Run detection on every Nth video frame.
	 *
	 * <p>
	 * 25 is roughly one frame per second of typical footage. This is the single biggest cost lever the
	 * node has: halving it doubles the inference bill for the whole file.
	 * </p>
	 */
	@ParamDoc(label = "Video Chop Rate", description = "Detect on every Nth video frame", min = "1")
	private int videoChopRate = 25;

	/**
	 * Longest edge a frame is scaled to before inference.
	 */
	@ParamDoc(label = "Scale Size (px)", description = "Rescale frames to this longest edge before inference", min = "1")
	private int videoScaleSize = 1024;

	/**
	 * Upper bound on the detections kept for one asset.
	 *
	 * <p>
	 * A busy clip yields detections per sampled frame, so the row count is the product of the frame
	 * count and the crowd size — a few minutes of street footage reaches five figures without this.
	 * Hitting the cap is reported through the {@code flag} port rather than passing silently.
	 * </p>
	 */
	@ParamDoc(label = "Max Detections", description = "Stop after this many detections for one asset", min = "1")
	private int maxDetections = 500;

	/**
	 * Keep only these classes. Empty keeps everything the model reports.
	 *
	 * <p>
	 * Names are matched case-insensitively against the labels file, and are validated against the
	 * loaded model at startup — a filter naming a class the model does not have would otherwise look
	 * exactly like a model that never detects anything.
	 * </p>
	 */
	@ParamDoc(label = "Class Filter", description = "Keep only these class names; empty keeps all of them")
	private Set<String> classFilter = Set.of();

	public String getModelPath() {
		return modelPath;
	}

	public ObjectDetectNodeOptions setModelPath(String modelPath) {
		this.modelPath = modelPath;
		return this;
	}

	public String getLabelsPath() {
		return labelsPath;
	}

	public ObjectDetectNodeOptions setLabelsPath(String labelsPath) {
		this.labelsPath = labelsPath;
		return this;
	}

	public boolean isUseGpu() {
		return useGpu;
	}

	public ObjectDetectNodeOptions setUseGpu(boolean useGpu) {
		this.useGpu = useGpu;
		return this;
	}

	public String getOnnxRuntimeLibPath() {
		return onnxRuntimeLibPath;
	}

	public ObjectDetectNodeOptions setOnnxRuntimeLibPath(String onnxRuntimeLibPath) {
		this.onnxRuntimeLibPath = onnxRuntimeLibPath;
		return this;
	}

	public float getMinConfidence() {
		return minConfidence;
	}

	public ObjectDetectNodeOptions setMinConfidence(float minConfidence) {
		this.minConfidence = minConfidence;
		return this;
	}

	public int getVideoChopRate() {
		return videoChopRate;
	}

	public ObjectDetectNodeOptions setVideoChopRate(int videoChopRate) {
		this.videoChopRate = videoChopRate;
		return this;
	}

	public int getVideoScaleSize() {
		return videoScaleSize;
	}

	public ObjectDetectNodeOptions setVideoScaleSize(int videoScaleSize) {
		this.videoScaleSize = videoScaleSize;
		return this;
	}

	public int getMaxDetections() {
		return maxDetections;
	}

	public ObjectDetectNodeOptions setMaxDetections(int maxDetections) {
		this.maxDetections = maxDetections;
		return this;
	}

	public Set<String> getClassFilter() {
		return classFilter;
	}

	public ObjectDetectNodeOptions setClassFilter(Set<String> classFilter) {
		this.classFilter = classFilter == null ? Set.of() : classFilter;
		return this;
	}

	/**
	 * The class filter, lower-cased, for matching against a detection label.
	 *
	 * @return an empty set when no filter is configured, meaning "keep everything"
	 */
	public Set<String> normalizedClassFilter() {
		Set<String> normalized = new LinkedHashSet<>();
		for (String name : classFilter) {
			if (name != null && !name.isBlank()) {
				normalized.add(name.trim().toLowerCase());
			}
		}
		return normalized;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (modelPath == null || modelPath.isBlank()) {
			errors.add("modelPath must not be empty");
		}

		if (labelsPath == null || labelsPath.isBlank()) {
			errors.add("labelsPath must not be empty");
		}

		// The upper bound matters as much as the lower one: 1.0 keeps only a detection the model is
		// perfectly certain of, which in practice is none of them.
		if (minConfidence < NATIVE_CONFIDENCE_FLOOR || minConfidence > 1) {
			errors.add("minConfidence must be between " + NATIVE_CONFIDENCE_FLOOR + " and 1, got " + minConfidence);
		}

		if (videoChopRate <= 0) {
			errors.add("videoChopRate must be positive, got " + videoChopRate);
		}

		if (videoScaleSize <= 0) {
			errors.add("videoScaleSize must be positive, got " + videoScaleSize);
		}

		if (maxDetections <= 0) {
			errors.add("maxDetections must be positive, got " + maxDetections);
		}

		if (classFilter == null) {
			errors.add("classFilter must not be null; use an empty set to keep every class");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
