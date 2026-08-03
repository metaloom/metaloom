package io.metaloom.cortex.node.color;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Options for the {@link DominantColorNode}.
 *
 * <p>
 * They fall into four groups: which regions to measure ({@link #includeWholeImage},
 * {@link #useDetections} and the static {@code region*} fields), how
 * hard to look ({@link #clusterCount}, {@link #maxSamples}, {@link #maxIterations}), how to name
 * what was found ({@link #achromaticChroma}, {@link #blackLightness}, {@link #whiteLightness}), and
 * what to keep ({@link #maxRegions}, {@link #minRegionPixels}, {@link #emitPalette}).
 * </p>
 */
public class DominantColorNodeOptions extends AbstractNodeOptions<DominantColorNodeOptions> {

	public static final String KEY = "dominant-color";

	/** Coordinate mode for the statically configured region. */
	public static final String NORMALIZED = "NORMALIZED";

	/** Coordinate mode for the statically configured region. */
	public static final String ABSOLUTE_PIXELS = "ABSOLUTE_PIXELS";

	// Declaration order is the order the editor renders these in - the harvester emits parameters in
	// field order. Keep the four groups described in the class javadoc together.

	/** How many colours to extract per region. */
	@ParamDoc(label = "Colours per Region",
		description = "How many colours to extract from each region, ranked by pixel share",
		min = "1", max = "16")
	private int clusterCount = 5;

	/** Upper bound on sampled pixels per region; the sampling stride is derived from it. */
	@ParamDoc(label = "Max Samples",
		description = "Upper bound on pixels visited per region; the sampling stride is derived from it",
		min = "256", max = "1000000")
	private int maxSamples = 40_000;

	/** Lloyd iteration budget. */
	@ParamDoc(label = "Max Iterations", description = "Clustering iteration budget", min = "1", max = "500")
	private int maxIterations = 30;

	/** Convergence threshold on the largest centroid shift, in Lab units. 0.5 is about a JND. */
	@ParamDoc(label = "Convergence Epsilon",
		description = "Stop once the largest colour shift falls below this; 0.5 is about the smallest "
			+ "difference a human can see",
		min = "0.01", step = "0.1")
	private double convergenceEpsilon = 0.5d;

	/** k-means++ seed. Fixed by default so the same image always yields the same palette. */
	@ParamDoc(label = "Seed", description = "Clustering seed. Fixed so the same image always yields the same palette")
	private long seed = 42L;

	/** Pixels with alpha below this are skipped rather than flattened onto a background. */
	@ParamDoc(label = "Alpha Threshold",
		description = "Pixels more transparent than this are ignored rather than blended with a background",
		min = "0", max = "255")
	private int alphaThreshold = 128;

	/** Measure the whole frame. */
	@ParamDoc(label = "Measure Whole Image", description = "Report the dominant colour of the full frame")
	private boolean includeWholeImage = true;

	/** Measure every bounding box wired into the node's {@code detections} port. */
	@ParamDoc(label = "Measure Detections",
		description = "Report a colour for every bounding box an upstream detector produced")
	private boolean useDetections = true;

	/** Static region left edge. */
	@ParamDoc(label = "Region X", description = "Left edge of a fixed region to measure", min = "0.0", step = "0.05")
	private double regionX = 0d;

	/** Static region top edge. */
	@ParamDoc(label = "Region Y", description = "Top edge of a fixed region to measure", min = "0.0", step = "0.05")
	private double regionY = 0d;

	/** Static region width. Zero disables the static region entirely. */
	@ParamDoc(label = "Region Width", description = "Width of a fixed region; 0 disables it", min = "0.0", step = "0.05")
	private double regionW = 0d;

	/** Static region height. Zero disables the static region entirely. */
	@ParamDoc(label = "Region Height", description = "Height of a fixed region; 0 disables it", min = "0.0", step = "0.05")
	private double regionH = 0d;

	/** Whether the static region is expressed in [0,1] fractions or in pixels. */
	@ParamDoc(label = "Region Coordinates",
		description = "Whether the fixed region is expressed as fractions of the image or in pixels",
		type = ParameterType.ENUM, values = { NORMALIZED, ABSOLUTE_PIXELS })
	private String regionCoordinates = NORMALIZED;

	/** Regions covering fewer usable pixels than this are dropped - k-means would be fitting noise. */
	@ParamDoc(label = "Min Region Pixels",
		description = "Regions smaller than this are dropped - clustering them would fit noise",
		min = "1")
	private int minRegionPixels = 64;

	/** Cap on detection regions. The whole-image and configured regions never count against it. */
	@ParamDoc(label = "Max Regions",
		description = "Largest-first cap on detection regions; the whole image and fixed region are exempt",
		min = "1")
	private int maxRegions = 32;

	/** Emit the full ranked palette per region, not only the dominant colour. */
	@ParamDoc(label = "Emit Palette",
		description = "Emit the full ranked palette per region rather than only the dominant colour")
	private boolean emitPalette = true;

	/** Chroma below which a colour is named on lightness alone - black, grey or white. */
	@ParamDoc(label = "Achromatic Threshold",
		description = "Below this saturation a colour is named black, grey or white instead of by hue",
		min = "0.0", max = "60.0", step = "1.0")
	private double achromaticChroma = ColorNamer.DEFAULT_ACHROMATIC_CHROMA;

	/** Lightness below which an achromatic colour is black. */
	@ParamDoc(label = "Black Threshold",
		description = "Lightness below which an unsaturated colour is called black",
		min = "0.0", max = "100.0", step = "1.0")
	private double blackLightness = ColorNamer.DEFAULT_BLACK_LIGHTNESS;

	/** Lightness at or above which an achromatic colour is white. */
	@ParamDoc(label = "White Threshold",
		description = "Lightness above which an unsaturated colour is called white",
		min = "0.0", max = "100.0", step = "1.0")
	private double whiteLightness = ColorNamer.DEFAULT_WHITE_LIGHTNESS;

	@Override
	protected DominantColorNodeOptions self() {
		return this;
	}

	public int getClusterCount() {
		return clusterCount;
	}

	public DominantColorNodeOptions setClusterCount(int clusterCount) {
		this.clusterCount = clusterCount;
		return this;
	}

	public int getMaxSamples() {
		return maxSamples;
	}

	public DominantColorNodeOptions setMaxSamples(int maxSamples) {
		this.maxSamples = maxSamples;
		return this;
	}

	public int getMaxIterations() {
		return maxIterations;
	}

	public DominantColorNodeOptions setMaxIterations(int maxIterations) {
		this.maxIterations = maxIterations;
		return this;
	}

	public double getConvergenceEpsilon() {
		return convergenceEpsilon;
	}

	public DominantColorNodeOptions setConvergenceEpsilon(double convergenceEpsilon) {
		this.convergenceEpsilon = convergenceEpsilon;
		return this;
	}

	public long getSeed() {
		return seed;
	}

	public DominantColorNodeOptions setSeed(long seed) {
		this.seed = seed;
		return this;
	}

	public int getAlphaThreshold() {
		return alphaThreshold;
	}

	public DominantColorNodeOptions setAlphaThreshold(int alphaThreshold) {
		this.alphaThreshold = alphaThreshold;
		return this;
	}

	public int getMinRegionPixels() {
		return minRegionPixels;
	}

	public DominantColorNodeOptions setMinRegionPixels(int minRegionPixels) {
		this.minRegionPixels = minRegionPixels;
		return this;
	}

	public int getMaxRegions() {
		return maxRegions;
	}

	public DominantColorNodeOptions setMaxRegions(int maxRegions) {
		this.maxRegions = maxRegions;
		return this;
	}

	public boolean isIncludeWholeImage() {
		return includeWholeImage;
	}

	public DominantColorNodeOptions setIncludeWholeImage(boolean includeWholeImage) {
		this.includeWholeImage = includeWholeImage;
		return this;
	}

	public boolean isUseDetections() {
		return useDetections;
	}

	public DominantColorNodeOptions setUseDetections(boolean useDetections) {
		this.useDetections = useDetections;
		return this;
	}

	public double getRegionX() {
		return regionX;
	}

	public DominantColorNodeOptions setRegionX(double regionX) {
		this.regionX = regionX;
		return this;
	}

	public double getRegionY() {
		return regionY;
	}

	public DominantColorNodeOptions setRegionY(double regionY) {
		this.regionY = regionY;
		return this;
	}

	public double getRegionW() {
		return regionW;
	}

	public DominantColorNodeOptions setRegionW(double regionW) {
		this.regionW = regionW;
		return this;
	}

	public double getRegionH() {
		return regionH;
	}

	public DominantColorNodeOptions setRegionH(double regionH) {
		this.regionH = regionH;
		return this;
	}

	public String getRegionCoordinates() {
		return regionCoordinates;
	}

	public DominantColorNodeOptions setRegionCoordinates(String regionCoordinates) {
		this.regionCoordinates = regionCoordinates;
		return this;
	}

	public double getAchromaticChroma() {
		return achromaticChroma;
	}

	public DominantColorNodeOptions setAchromaticChroma(double achromaticChroma) {
		this.achromaticChroma = achromaticChroma;
		return this;
	}

	public double getBlackLightness() {
		return blackLightness;
	}

	public DominantColorNodeOptions setBlackLightness(double blackLightness) {
		this.blackLightness = blackLightness;
		return this;
	}

	public double getWhiteLightness() {
		return whiteLightness;
	}

	public DominantColorNodeOptions setWhiteLightness(double whiteLightness) {
		this.whiteLightness = whiteLightness;
		return this;
	}

	public boolean isEmitPalette() {
		return emitPalette;
	}

	public DominantColorNodeOptions setEmitPalette(boolean emitPalette) {
		this.emitPalette = emitPalette;
		return this;
	}

	/**
	 * @return true when a static region is configured
	 */
	public boolean hasStaticRegion() {
		return regionW > 0 && regionH > 0;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (clusterCount < 1 || clusterCount > 16) {
			errors.add("clusterCount must be in [1, 16], got " + clusterCount);
		}
		if (maxSamples < 256 || maxSamples > 1_000_000) {
			errors.add("maxSamples must be in [256, 1000000], got " + maxSamples);
		}
		if (maxIterations < 1 || maxIterations > 500) {
			errors.add("maxIterations must be in [1, 500], got " + maxIterations);
		}
		if (convergenceEpsilon <= 0) {
			errors.add("convergenceEpsilon must be positive, got " + convergenceEpsilon);
		}
		if (alphaThreshold < 0 || alphaThreshold > 255) {
			errors.add("alphaThreshold must be in [0, 255], got " + alphaThreshold);
		}
		if (minRegionPixels < 1) {
			errors.add("minRegionPixels must be positive, got " + minRegionPixels);
		}
		if (maxRegions < 1) {
			errors.add("maxRegions must be positive, got " + maxRegions);
		}

		if (!NORMALIZED.equals(regionCoordinates) && !ABSOLUTE_PIXELS.equals(regionCoordinates)) {
			errors.add("regionCoordinates must be " + NORMALIZED + " or " + ABSOLUTE_PIXELS + ", got " + regionCoordinates);
		}

		// A half-specified region is a typo, not a disabled region - say so rather than silently
		// ignoring it.
		if (regionW > 0 || regionH > 0 || regionX > 0 || regionY > 0) {
			if (regionW <= 0 || regionH <= 0) {
				errors.add("region must have a positive regionW and regionH, got " + regionW + "x" + regionH);
			}
			if (regionX < 0 || regionY < 0) {
				errors.add("region origin must not be negative, got " + regionX + "," + regionY);
			}
			if (NORMALIZED.equals(regionCoordinates) && regionW > 0 && regionH > 0
				&& (regionX + regionW > 1.0d || regionY + regionH > 1.0d)) {
				errors.add("a NORMALIZED region must fit inside the unit square, got "
					+ regionX + "," + regionY + " " + regionW + "x" + regionH);
			}
		}

		// Configured to do nothing at all. Left unchecked this reads as a broken pipeline (every
		// asset skipped) rather than as a broken configuration.
		if (!includeWholeImage && !useDetections && !hasStaticRegion()) {
			errors.add("at least one region source must be enabled (includeWholeImage, useDetections or a configured region)");
		}

		if (achromaticChroma < 0 || achromaticChroma > 60) {
			errors.add("achromaticChroma must be in [0, 60], got " + achromaticChroma);
		}
		if (blackLightness < 0) {
			errors.add("blackLightness must not be negative, got " + blackLightness);
		}
		if (whiteLightness > 100) {
			errors.add("whiteLightness must be at most 100, got " + whiteLightness);
		}
		if (blackLightness >= whiteLightness) {
			errors.add("blackLightness (" + blackLightness + ") must be below whiteLightness (" + whiteLightness + ")");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
