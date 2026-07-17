package io.metaloom.cortex.node.facedetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class FacedetectNodeOptions extends AbstractNodeOptions<FacedetectNodeOptions> {

	public static final String KEY = "facedetection";

	private static final String DEFAULT_PACK_PATH = "packs/Pikachu";

	@Override
	protected FacedetectNodeOptions self() {
		return this;
	}

	/**
	 * Process only every nth video frame.
	 */
	private int videoChopRate = 5;

	/**
	 * Defines the minimum of detections that may form a dedicated cluster.
	 */
	public int faceClusterMinimum = 2;

	/**
	 * Defines the minimum radius that is being utilized to cluster faces together.
	 */
	public float faceClusterEPS = 0.6f;

	/**
	 * Defines the size to which every frame will be increased in either width or height before processing. Higher resolution increased detection precision but
	 * also detection time.
	 */
	private int videoScaleSize = 384;

	/**
	 * Defines the height factor in respect to the total frame height which controls whether a found face will be processed further.
	 */
	private float minFaceHeightFactor = 0.05f;

	/**
	 * Defines the inspireface model pack path.
	 */
	private String inspirefacePackPath = DEFAULT_PACK_PATH;

	/**
	 * Set of enabled face detection capabilities.
	 */
	private Set<FacedetectNodeCapabilities> capabilities = Set.of(FacedetectNodeCapabilities.INSPIREFACE);

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
		
		// capabilities must not be empty
		if (capabilities == null || capabilities.isEmpty()) {
			errors.add("capabilities must not be empty");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
