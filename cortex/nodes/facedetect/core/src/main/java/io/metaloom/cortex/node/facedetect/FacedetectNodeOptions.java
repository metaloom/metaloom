package io.metaloom.cortex.node.facedetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class FacedetectNodeOptions extends AbstractNodeOptions<FacedetectNodeOptions> {

	public static final String KEY = "facedetection";

	private static final String DEFAULT_PACK_PATH = "packs/Pikachu";

	/** Matches the recognition model in the default Pikachu pack. */
	public static final String DEFAULT_EMBEDDING_MODEL = "inspireface-r18";

	/** The {@code embedding.type} this node writes. */
	public static final String EMBEDDING_TYPE = "face";

	@Override
	protected FacedetectNodeOptions self() {
		return this;
	}

	/**
	 * Process only every nth video frame.
	 */
	@ParamDoc(label = "Video Chop Rate", description = "Process every Nth video frame", min = "1")
	private int videoChopRate = 5;

	/**
	 * Defines the minimum of detections that may form a dedicated cluster.
	 */
	@ParamDoc(label = "Min Cluster Size", description = "Minimum detections to form a cluster")
	public int faceClusterMinimum = 2;

	/**
	 * Defines the minimum radius that is being utilized to cluster faces together.
	 */
	@ParamDoc(label = "Cluster Radius", description = "DBSCAN cluster radius threshold", min = "0.0", max = "2.0", step = "0.05")
	public float faceClusterEPS = 0.6f;

	/**
	 * Defines the size to which every frame will be increased in either width or height before processing. Higher resolution increased detection precision but
	 * also detection time.
	 */
	@ParamDoc(label = "Scale Size (px)", description = "Rescale video frames to this size")
	private int videoScaleSize = 384;

	/**
	 * Defines the height factor in respect to the total frame height which controls whether a found face will be processed further.
	 */
	@ParamDoc(label = "Min Face Height Factor", min = "0.0", max = "1.0")
	private float minFaceHeightFactor = 0.05f;

	/**
	 * Maximum head rotation, in degrees, accepted for a face found in a <strong>video</strong> frame.
	 *
	 * <p>
	 * A face turned further than this on any axis is discarded however confident the detector is, because a face in profile yields a poor embedding
	 * while its detection score stays high - so score alone cannot filter them.
	 * </p>
	 *
	 * <p>
	 * The 30 degree default suits footage shot towards the camera and rejects a surprising amount of everything else: two people talking to each other
	 * sit at 40-80 degrees of yaw and are dropped entirely, so such a clip reports no faces at all. Raise it for conversational or side-on footage, or
	 * set 180 to accept every orientation when nothing downstream needs an embedding.
	 * </p>
	 *
	 * <p>
	 * ⚠️ Applies to the video path only. The image path has never had this gate, so the same frame can yield faces as a file and none as a video.
	 * </p>
	 */
	@ParamDoc(label = "Max Face Angle (deg)", description = "Discard video faces turned further than this; 180 accepts any orientation", min = "0.0", max = "180.0", step = "5")
	private float maxFaceAngle = InspireFacedetector.DEFAULT_MAX_FACE_ANGLE;

	/**
	 * Defines the inspireface model pack path.
	 */
	@ParamDoc(label = "Model Pack Path")
	private String inspirefacePackPath = DEFAULT_PACK_PATH;

	/**
	 * Whether a recognition embedding is computed for each detected face and written to Loom.
	 *
	 * <p>
	 * A bounding box says where a face is; only the embedding can say whether two faces are the same person. Without it the node produces detections
	 * that can be drawn but never matched or clustered, which is what it did before this option existed.
	 * </p>
	 *
	 * <p>
	 * Costs one extra model pass per face, so it can be switched off for a run that only needs boxes.
	 * </p>
	 */
	@ParamDoc(label = "Compute Embeddings", description = "Compute a recognition embedding per face so faces can be matched and clustered")
	private boolean embeddingsEnabled = true;

	/**
	 * The readable model identifier recorded on every embedding this node writes.
	 *
	 * <p>
	 * It is part of the embedding's identity in Loom, so raising it is how a model upgrade is performed: the new vectors land beside the old ones
	 * rather than overwriting them, both remain queryable, and the old set is dropped once the new one has been shown to be better. Changing the model
	 * without changing this value would silently mix two incompatible vector populations under one name.
	 * </p>
	 */
	@ParamDoc(label = "Embedding Model", description = "Model identifier recorded on each embedding; change it whenever the model pack changes")
	private String embeddingModel = DEFAULT_EMBEDDING_MODEL;

	/**
	 * Set of enabled face detection capabilities.
	 */
	@ParamDoc(label = "Backends", values = { "INSPIREFACE", "DLIB" })
	private Set<FacedetectNodeCapabilities> capabilities = Set.of(FacedetectNodeCapabilities.INSPIREFACE);

	public boolean isEmbeddingsEnabled() {
		return embeddingsEnabled;
	}

	public FacedetectNodeOptions setEmbeddingsEnabled(boolean embeddingsEnabled) {
		this.embeddingsEnabled = embeddingsEnabled;
		return this;
	}

	public String getEmbeddingModel() {
		return embeddingModel;
	}

	public FacedetectNodeOptions setEmbeddingModel(String embeddingModel) {
		this.embeddingModel = embeddingModel;
		return this;
	}

	public int getVideoChopRate() {
		return videoChopRate;
	}

	public FacedetectNodeOptions setVideoChopRate(int videoChopRate) {
		this.videoChopRate = videoChopRate;
		return this;
	}

	public int getVideoScaleSize() {
		return videoScaleSize;
	}

	public FacedetectNodeOptions setVideoScaleSize(int videoScaleSize) {
		this.videoScaleSize = videoScaleSize;
		return this;
	}

	public int getFaceClusterMinimum() {
		return faceClusterMinimum;
	}

	public FacedetectNodeOptions setFaceClusterMinimum(int faceClusterMinimum) {
		this.faceClusterMinimum = faceClusterMinimum;
		return this;
	}

	public float getFaceClusterEPS() {
		return faceClusterEPS;
	}

	public FacedetectNodeOptions setFaceClusterEPS(float faceClusterEPS) {
		this.faceClusterEPS = faceClusterEPS;
		return this;
	}

	public float getMinFaceHeightFactor() {
		return minFaceHeightFactor;
	}

	public float getMaxFaceAngle() {
		return maxFaceAngle;
	}

	public FacedetectNodeOptions setMaxFaceAngle(float maxFaceAngle) {
		this.maxFaceAngle = maxFaceAngle;
		return this;
	}

	public FacedetectNodeOptions setMinFaceHeightFactor(float minFaceHeightFactor) {
		this.minFaceHeightFactor = minFaceHeightFactor;
		return this;
	}

	public String getInspirefacePackPath() {
		return inspirefacePackPath;
	}

	public FacedetectNodeOptions setInspirefacePackPath(String inspirefacePackPath) {
		this.inspirefacePackPath = inspirefacePackPath;
		return this;
	}

	public Set<FacedetectNodeCapabilities> getCapabilities() {
		return capabilities;
	}

	public void setCapabilities(Set<FacedetectNodeCapabilities> capabilities) {
		this.capabilities = capabilities;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// videoChopRate must be positive
		if (videoChopRate <= 0) {
			errors.add("videoChopRate must be positive, got " + videoChopRate);
		}
		
		// videoScaleSize must be positive
		if (videoScaleSize <= 0) {
			errors.add("videoScaleSize must be positive, got " + videoScaleSize);
		}
		
		// faceClusterMinimum must be positive
		if (faceClusterMinimum <= 0) {
			errors.add("faceClusterMinimum must be positive, got " + faceClusterMinimum);
		}
		
		// faceClusterEPS must be positive
		if (faceClusterEPS <= 0) {
			errors.add("faceClusterEPS must be positive, got " + faceClusterEPS);
		}
		
		// minFaceHeightFactor must be between 0 and 1
		if (minFaceHeightFactor <= 0 || minFaceHeightFactor > 1) {
			errors.add("minFaceHeightFactor must be between 0 and 1, got " + minFaceHeightFactor);
		}
		
		// inspirefacePackPath must not be empty
		if (inspirefacePackPath == null || inspirefacePackPath.isBlank()) {
			errors.add("inspirefacePackPath must not be empty");
		}

		// An unnamed model would make every embedding this node writes indistinguishable from those of
		// the next model, which is exactly what the identifier exists to prevent.
		if (embeddingsEnabled && (embeddingModel == null || embeddingModel.isBlank())) {
			errors.add("embeddingModel must not be empty when embeddingsEnabled is true");
		}
		
		// capabilities must not be empty
		if (capabilities == null || capabilities.isEmpty()) {
			errors.add("capabilities must not be empty");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
