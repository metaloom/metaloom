package io.metaloom.cortex.node.color;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link DominantColorNode}.
 *
 * <p>
 * They fall into four groups: which regions to measure ({@link #includeWholeImage},
 * {@link #useDetections}, {@link #detectionSources} and the static {@code region*} fields), how
 * hard to look ({@link #clusterCount}, {@link #maxSamples}, {@link #maxIterations}), how to name
 * what was found ({@link #achromaticChroma}, {@link #blackLightness}, {@link #whiteLightness}), and
 * what to keep ({@link #maxRegions}, {@link #minRegionPixels}, {@link #emitPalette}).
 * </p>
 */
public class DominantColorNodeOptions extends AbstractNodeOptions<DominantColorNodeOptions> {

	public static final String KEY = "dominant-color";

	public static final List<String> DEFAULT_DETECTION_SOURCES = List.of("facedetect");

	/** Coordinate mode for the statically configured region. */
	public static final String NORMALIZED = "NORMALIZED";

	/** Coordinate mode for the statically configured region. */
	public static final String ABSOLUTE_PIXELS = "ABSOLUTE_PIXELS";

	/** How many colours to extract per region. */
	private int clusterCount = 5;

	/** Upper bound on sampled pixels per region; the sampling stride is derived from it. */
	private int maxSamples = 40_000;

	/** Lloyd iteration budget. */
	private int maxIterations = 30;

	/** Convergence threshold on the largest centroid shift, in Lab units. 0.5 is about a JND. */
	private double convergenceEpsilon = 0.5d;

	/** k-means++ seed. Fixed by default so the same image always yields the same palette. */
	private long seed = 42L;

	/** Pixels with alpha below this are skipped rather than flattened onto a background. */
	private int alphaThreshold = 128;

	/** Regions covering fewer usable pixels than this are dropped - k-means would be fitting noise. */
	private int minRegionPixels = 64;

	/** Cap on detection regions. The whole-image and configured regions never count against it. */
	private int maxRegions = 32;

	/** Measure the whole frame. */
	private boolean includeWholeImage = true;

	/** Measure every bounding box supplied by the configured upstream detectors. */
	private boolean useDetections = true;

	/** Upstream node ids whose {@code detections} output is consumed, in emission order. */
	private List<String> detectionSources = new ArrayList<>(DEFAULT_DETECTION_SOURCES);

	/** Static region left edge. */
	private double regionX = 0d;

	/** Static region top edge. */
	private double regionY = 0d;

	/** Static region width. Zero disables the static region entirely. */
	private double regionW = 0d;

	/** Static region height. Zero disables the static region entirely. */
	private double regionH = 0d;

	/** Whether the static region is expressed in [0,1] fractions or in pixels. */
	private String regionCoordinates = NORMALIZED;

	/** Chroma below which a colour is named on lightness alone - black, grey or white. */
	private double achromaticChroma = ColorNamer.DEFAULT_ACHROMATIC_CHROMA;

	/** Lightness below which an achromatic colour is black. */
	private double blackLightness = ColorNamer.DEFAULT_BLACK_LIGHTNESS;

	/** Lightness at or above which an achromatic colour is white. */
	private double whiteLightness = ColorNamer.DEFAULT_WHITE_LIGHTNESS;

	/** Emit the full ranked palette per region, not only the dominant colour. */
	private boolean emitPalette = true;

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

	public List<String> getDetectionSources() {
		return detectionSources;
	}

	public DominantColorNodeOptions setDetectionSources(List<String> detectionSources) {
		this.detectionSources = detectionSources;
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

		if (useDetections) {
			if (detectionSources == null || detectionSources.isEmpty()) {
				errors.add("detectionSources must not be empty when useDetections is set");
			} else {
				for (String source : detectionSources) {
					if (source == null || source.isBlank()) {
						errors.add("detectionSources must not contain blank entries");
					}
				}
			}
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
