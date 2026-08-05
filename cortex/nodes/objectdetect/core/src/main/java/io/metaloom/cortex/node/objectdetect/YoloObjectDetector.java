package io.metaloom.cortex.node.objectdetect;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.opencv.core.Mat;
import io.metaloom.opencv.imgproc.Imgproc;
import io.metaloom.video4j.impl.MatProvider;
import io.metaloom.video4j.opencv.CVUtils;
import io.metaloom.yolo4j.Detection;
import io.metaloom.yolo4j.YoloLib;

/**
 * The {@link ObjectDetector} that actually runs YOLO, and the one place that knows about the
 * library's lifecycle constraints.
 *
 * <p>
 * <strong>One model per JVM.</strong> {@code YoloLib.init} populates a single
 * {@code static std::unique_ptr<YOLODetector>} on the native side and throws on a second call; there
 * is no unload and no swap. This class therefore initializes lazily on first use, remembers what it
 * initialized with, and refuses — loudly, naming both models — when a second node instance asks for
 * a different one. Failing here beats the alternative, which is silently detecting with somebody
 * else's model.
 * </p>
 *
 * <p>
 * <strong>Not thread-safe.</strong> The native detector is an unguarded global, so {@link #detect}
 * is {@code synchronized}. The node also declares {@code defaultConcurrency = 1}; the lock is what
 * makes that a guarantee rather than a default an author can raise.
 * </p>
 */
public class YoloObjectDetector implements ObjectDetector {

	private static final Logger log = LoggerFactory.getLogger(YoloObjectDetector.class);

	/**
	 * The system property yolo4j reads to locate {@code libonnxruntime.so.1}.
	 *
	 * <p>
	 * Its fallback walks up from the model path looking for an {@code onnxruntime-*} directory, which
	 * only finds anything inside yolo4j's own checkout layout. A worker with the runtime installed
	 * anywhere else has to say where it is.
	 * </p>
	 */
	private static final String ONNXRUNTIME_LIB_PROPERTY = "yolo4j.onnxruntime.lib";

	/**
	 * What {@code YoloLib} was initialized with in this JVM, or null before anything has been loaded.
	 *
	 * <p>
	 * Static because the thing it describes is: {@code YoloLib} holds one native detector for the
	 * whole process. Without this, a second instance could only ask "is it initialized?", which is not
	 * enough to tell "somebody already loaded the model you want" — the common case, and fine — from
	 * "somebody loaded a different one" — the one that has to fail.
	 * </p>
	 */
	private static Loaded loaded;

	/** The model, labels and device a {@code YoloLib.init} was performed with. */
	private record Loaded(String modelPath, String labelsPath, boolean useGpu) {
	}

	private final String modelPath;
	private final String labelsPath;
	private final boolean useGpu;
	private final String onnxRuntimeLibPath;

	private boolean initialized;

	public YoloObjectDetector(String modelPath, String labelsPath, boolean useGpu, String onnxRuntimeLibPath) {
		this.modelPath = modelPath;
		this.labelsPath = labelsPath;
		this.useGpu = useGpu;
		this.onnxRuntimeLibPath = onnxRuntimeLibPath;
	}

	@Override
	public synchronized List<ObjectDetection> detect(Mat frame) {
		ensureInitialized();
		List<ObjectDetection> detections = new ArrayList<>();
		// drawBoundingBoxes = false: the native side would otherwise paint onto the Mat we handed it,
		// which is the caller's frame and may be a decoded video frame reused downstream.
		for (Detection detection : YoloLib.detect(frame, false)) {
			io.metaloom.yolo4j.BoundingBox box = detection.box();
			detections.add(new ObjectDetection(
				new BoundingBox(box.x(), box.y(), box.width(), box.height()),
				detection.conf(),
				detection.classId(),
				detection.label()));
		}
		return detections;
	}

	/**
	 * Detect in a decoded image, owning the {@code Mat} for the duration.
	 *
	 * <p>
	 * Written out here rather than delegating to {@code YoloLib.detect(BufferedImage, boolean)},
	 * which allocates a {@code Mat} through {@code MatProvider} and never releases it — one leaked
	 * native buffer per item, in a worker that does not restart.
	 * </p>
	 */
	@Override
	public synchronized List<ObjectDetection> detect(BufferedImage image) {
		Mat mat = MatProvider.mat(image, Imgproc.COLOR_BGRA2BGR565);
		try {
			CVUtils.bufferedImageToMat(image, mat);
			return detect(mat);
		} finally {
			MatProvider.released(mat);
		}
	}

	@Override
	public synchronized List<String> labels() {
		ensureInitialized();
		return YoloLib.labels();
	}

	/**
	 * Load the model, once per JVM, and fail with something actionable when we cannot.
	 *
	 * @throws IllegalStateException when the library is already serving a <em>different</em> model
	 */
	private void ensureInitialized() {
		if (initialized) {
			return;
		}
		requireFile(modelPath, "model");
		requireFile(labelsPath, "labels file");

		synchronized (YoloObjectDetector.class) {
			Loaded wanted = new Loaded(modelPath, labelsPath, useGpu);
			if (loaded != null || YoloLib.isInitialized()) {
				// Someone got here first. Adopting their detector is correct when it is the same one we
				// would have loaded - two graph nodes on one model differing only in threshold or class
				// filter is an ordinary pipeline, and refusing it would be refusing a legal graph.
				if (wanted.equals(loaded)) {
					initialized = true;
					return;
				}
				throw new IllegalStateException(
					"YoloLib is already initialized in this worker with " + describe(loaded)
						+ " and cannot load a second model. This instance asked for " + describe(wanted)
						+ ". Run one objectdetect model per Cortex worker.");
			}

			if (onnxRuntimeLibPath != null && !onnxRuntimeLibPath.isBlank()) {
				System.setProperty(ONNXRUNTIME_LIB_PROPERTY, onnxRuntimeLibPath);
			}

			log.info("Initializing YOLO with model {} and labels {} (GPU: {})", modelPath, labelsPath, useGpu);
			YoloLib.init(modelPath, labelsPath, useGpu);
			loaded = wanted;
			initialized = true;
			log.info("YOLO ready with {} classes", YoloLib.labels().size());
		}
	}

	/**
	 * Name a configuration in an error, including the case where the library was initialized by
	 * something other than this class and we genuinely do not know what it holds.
	 */
	private static String describe(Loaded config) {
		if (config == null) {
			return "a model loaded outside this node";
		}
		return "model '" + config.modelPath() + "' (labels '" + config.labelsPath() + "', GPU: " + config.useGpu() + ")";
	}

	private static void requireFile(String path, String what) {
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("No " + what + " path configured for the objectdetect node");
		}
		Path resolved = Paths.get(path);
		if (!Files.exists(resolved)) {
			// Wrapped rather than thrown as-is: this runs inside compute(), which reports a node failure,
			// and a checked FileNotFoundException there would only be caught and rethrown.
			throw new IllegalStateException("Unable to locate the objectdetect " + what + " at '"
				+ resolved.toAbsolutePath() + "'", new FileNotFoundException(path));
		}
	}
}
