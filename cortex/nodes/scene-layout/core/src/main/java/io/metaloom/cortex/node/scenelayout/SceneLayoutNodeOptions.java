package io.metaloom.cortex.node.scenelayout;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link SceneLayoutNode}.
 *
 * <p>
 * The node joins detector bounding boxes to a depth map. {@link #depthNodeId} names the upstream
 * {@code depthmap} node; {@link #detectionSources} names the upstream detector nodes whose
 * {@code detections} output is consumed, in priority order. When no upstream output is present and
 * {@link #allowLoomFallback} is set, the boxes are read back from Loom instead.
 * </p>
 *
 * <p>
 * The remaining fields are the thresholds behind the relation predicates. Their defaults were
 * chosen to be conservative: it is better to say {@code SAME_DEPTH} than to assert a confident
 * ordering that monocular depth cannot actually support.
 * </p>
 */
public class SceneLayoutNodeOptions extends AbstractNodeOptions<SceneLayoutNodeOptions> {

	public static final String KEY = "scene-layout";

	public static final List<String> DEFAULT_DETECTION_SOURCES = List.of("facedetect");

	private String depthNodeId = "depthmap";

	private List<String> detectionSources = new ArrayList<>(DEFAULT_DETECTION_SOURCES);

	/** Read detections back from Loom when no upstream node supplied them. */
	private boolean allowLoomFallback = true;

	/** Fraction inset on each side before sampling depth. 0.25 keeps the central 50%. */
	private double coreInset = 0.25;

	/** Objects whose sampling core covers fewer pixels than this are dropped - the statistic would be noise. */
	private int minCorePixels = 16;

	/** |z| above which a depth ordering is asserted rather than SAME_DEPTH. */
	private double depthZThreshold = 1.0;

	/** Overlap (as a fraction of the smaller box) needed before occlusion is considered. */
	private double occlusionMinOverlap = 0.05;

	/** Intersection over the contained box's area needed to assert CONTAINS. */
	private double containmentRatio = 0.85;

	/** Gap over mean box size below which two same-depth objects are NEXT_TO. */
	private double nextToMaxGap = 0.5;

	/** Scene depth quantile at or above which an object is FOREGROUND. */
	private double foregroundQuantile = 0.66;

	/** Scene depth quantile at or below which an object is BACKGROUND. */
	private double backgroundQuantile = 0.33;

	/** Largest-first cap on objects; relations are O(n^2). */
	private int maxObjects = 40;

	private int maxRelations = 200;

	private boolean emitPhrases = true;

	@Override
	protected SceneLayoutNodeOptions self() {
		return this;
	}

	public String getDepthNodeId() {
		return depthNodeId;
	}

	public SceneLayoutNodeOptions setDepthNodeId(String depthNodeId) {
		this.depthNodeId = depthNodeId;
		return this;
	}

	public List<String> getDetectionSources() {
		return detectionSources;
	}

	public SceneLayoutNodeOptions setDetectionSources(List<String> detectionSources) {
		this.detectionSources = detectionSources;
		return this;
	}

	public boolean isAllowLoomFallback() {
		return allowLoomFallback;
	}

	public SceneLayoutNodeOptions setAllowLoomFallback(boolean allowLoomFallback) {
		this.allowLoomFallback = allowLoomFallback;
		return this;
	}

	public double getCoreInset() {
		return coreInset;
	}

	public SceneLayoutNodeOptions setCoreInset(double coreInset) {
		this.coreInset = coreInset;
		return this;
	}

	public int getMinCorePixels() {
		return minCorePixels;
	}

	public SceneLayoutNodeOptions setMinCorePixels(int minCorePixels) {
		this.minCorePixels = minCorePixels;
		return this;
	}

	public double getDepthZThreshold() {
		return depthZThreshold;
	}

	public SceneLayoutNodeOptions setDepthZThreshold(double depthZThreshold) {
		this.depthZThreshold = depthZThreshold;
		return this;
	}

	public double getOcclusionMinOverlap() {
		return occlusionMinOverlap;
	}

	public SceneLayoutNodeOptions setOcclusionMinOverlap(double occlusionMinOverlap) {
		this.occlusionMinOverlap = occlusionMinOverlap;
		return this;
	}

	public double getContainmentRatio() {
		return containmentRatio;
	}

	public SceneLayoutNodeOptions setContainmentRatio(double containmentRatio) {
		this.containmentRatio = containmentRatio;
		return this;
	}

	public double getNextToMaxGap() {
		return nextToMaxGap;
	}

	public SceneLayoutNodeOptions setNextToMaxGap(double nextToMaxGap) {
		this.nextToMaxGap = nextToMaxGap;
		return this;
	}

	public double getForegroundQuantile() {
		return foregroundQuantile;
	}

	public SceneLayoutNodeOptions setForegroundQuantile(double foregroundQuantile) {
		this.foregroundQuantile = foregroundQuantile;
		return this;
	}

	public double getBackgroundQuantile() {
		return backgroundQuantile;
	}

	public SceneLayoutNodeOptions setBackgroundQuantile(double backgroundQuantile) {
		this.backgroundQuantile = backgroundQuantile;
		return this;
	}

	public int getMaxObjects() {
		return maxObjects;
	}

	public SceneLayoutNodeOptions setMaxObjects(int maxObjects) {
		this.maxObjects = maxObjects;
		return this;
	}

	public int getMaxRelations() {
		return maxRelations;
	}

	public SceneLayoutNodeOptions setMaxRelations(int maxRelations) {
		this.maxRelations = maxRelations;
		return this;
	}

	public boolean isEmitPhrases() {
		return emitPhrases;
	}

	public SceneLayoutNodeOptions setEmitPhrases(boolean emitPhrases) {
		this.emitPhrases = emitPhrases;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (depthNodeId == null || depthNodeId.isBlank()) {
			errors.add("depthNodeId must not be empty");
		}
		if (detectionSources == null || detectionSources.isEmpty()) {
			errors.add("detectionSources must not be empty");
		} else {
			for (String source : detectionSources) {
				if (source == null || source.isBlank()) {
					errors.add("detectionSources must not contain blank entries");
				}
			}
		}
		if (coreInset < 0 || coreInset >= 0.5) {
			errors.add("coreInset must be in [0, 0.5), got " + coreInset);
		}
		if (minCorePixels <= 0) {
			errors.add("minCorePixels must be positive, got " + minCorePixels);
		}
		if (depthZThreshold <= 0) {
			errors.add("depthZThreshold must be positive, got " + depthZThreshold);
		}
		if (occlusionMinOverlap < 0 || occlusionMinOverlap > 1) {
			errors.add("occlusionMinOverlap must be in [0, 1], got " + occlusionMinOverlap);
		}
		if (containmentRatio <= 0 || containmentRatio > 1) {
			errors.add("containmentRatio must be in (0, 1], got " + containmentRatio);
		}
		if (nextToMaxGap <= 0) {
			errors.add("nextToMaxGap must be positive, got " + nextToMaxGap);
		}
		if (foregroundQuantile <= 0 || foregroundQuantile > 1) {
			errors.add("foregroundQuantile must be in (0, 1], got " + foregroundQuantile);
		}
		if (backgroundQuantile < 0 || backgroundQuantile >= 1) {
			errors.add("backgroundQuantile must be in [0, 1), got " + backgroundQuantile);
		}
		if (backgroundQuantile >= foregroundQuantile) {
			errors.add("backgroundQuantile (" + backgroundQuantile + ") must be below foregroundQuantile (" + foregroundQuantile + ")");
		}
		if (maxObjects <= 0) {
			errors.add("maxObjects must be positive, got " + maxObjects);
		}
		if (maxRelations <= 0) {
			errors.add("maxRelations must be positive, got " + maxRelations);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
