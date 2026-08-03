package io.metaloom.cortex.node.scenelayout;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link SceneLayoutNode}.
 *
 * <p>
 * The node joins detector bounding boxes to a depth map. Both arrive on declared input ports -
 * {@code depth} and {@code detections} - so which upstream nodes supply them is a property of the
 * wired graph, not of these options. When no detector is wired and {@link #allowLoomFallback} is
 * set, the boxes are read back from Loom instead.
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

	/** Read detections back from Loom when nothing is wired into the detections port. */
	@ParamDoc(label = "Loom Fallback", description = "Read detections back from Loom when no upstream node supplied them")
	private boolean allowLoomFallback = true;

	/** Fraction inset on each side before sampling depth. 0.25 keeps the central 50%. */
	@ParamDoc(label = "Core Inset",
		description = "Fraction inset per side before sampling depth; keeps background corners out of the statistic",
		min = "0.0", max = "0.49", step = "0.05")
	private double coreInset = 0.25;

	/**
	 * Objects whose sampling core covers fewer pixels than this are dropped - the statistic would be noise.
	 *
	 * <p>
	 * Hidden: {@code SceneLayoutDescriptorProvider}, the golden contract for this node, never advertised
	 * it. Surfacing it is a contract change and belongs in its own reviewed commit.
	 * </p>
	 */
	@ParamDoc(hidden = true)
	private int minCorePixels = 16;

	/** |z| above which a depth ordering is asserted rather than SAME_DEPTH. */
	@ParamDoc(label = "Depth Threshold",
		description = "Depth separation, in units of the objects' own spread, before an ordering is asserted",
		min = "0.0", step = "0.1")
	private double depthZThreshold = 1.0;

	/** Overlap (as a fraction of the smaller box) needed before occlusion is considered. */
	@ParamDoc(label = "Min Occlusion Overlap",
		description = "Overlap of the smaller box needed before occlusion is reported",
		min = "0.0", max = "1.0", step = "0.05")
	private double occlusionMinOverlap = 0.05;

	/** Intersection over the contained box's area needed to assert CONTAINS. */
	@ParamDoc(label = "Containment Ratio",
		description = "How much of a box must lie inside another to call it contained",
		min = "0.0", max = "1.0", step = "0.05")
	private double containmentRatio = 0.85;

	/** Gap over mean box size below which two same-depth objects are NEXT_TO. */
	@ParamDoc(label = "Next-To Max Gap",
		description = "Gap over mean box size below which two same-depth objects count as adjacent",
		min = "0.0", step = "0.1")
	private double nextToMaxGap = 0.5;

	/** Scene depth quantile at or above which an object is FOREGROUND. */
	@ParamDoc(label = "Foreground Quantile",
		description = "Scene depth quantile at or above which an object is foreground",
		min = "0.0", max = "1.0", step = "0.01")
	private double foregroundQuantile = 0.66;

	/** Scene depth quantile at or below which an object is BACKGROUND. */
	@ParamDoc(label = "Background Quantile",
		description = "Scene depth quantile at or below which an object is background",
		min = "0.0", max = "1.0", step = "0.01")
	private double backgroundQuantile = 0.33;

	/** Largest-first cap on objects; relations are O(n^2). */
	@ParamDoc(label = "Max Objects", description = "Largest-first cap; relations grow with the square of this", min = "1")
	private int maxObjects = 40;

	@ParamDoc(label = "Max Relations", description = "Cap on emitted relations, strongest kept first", min = "1")
	private int maxRelations = 200;

	@ParamDoc(label = "Emit Phrases", description = "Include readable sentences alongside the structured relations")
	private boolean emitPhrases = true;

	@Override
	protected SceneLayoutNodeOptions self() {
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
