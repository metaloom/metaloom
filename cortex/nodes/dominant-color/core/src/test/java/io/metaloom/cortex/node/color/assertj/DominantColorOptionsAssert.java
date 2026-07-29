package io.metaloom.cortex.node.color.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.color.DominantColorNodeOptions;

/**
 * AssertJ assertions for {@link DominantColorNodeOptions}.
 */
public class DominantColorOptionsAssert extends AbstractCortexNodeOptionsAssert<DominantColorOptionsAssert, DominantColorNodeOptions> {

	public DominantColorOptionsAssert(DominantColorNodeOptions actual) {
		super(actual, DominantColorOptionsAssert.class);
	}

	public DominantColorOptionsAssert hasClusterCount(int expected) {
		isNotNull();
		if (actual.getClusterCount() != expected) {
			failWithMessage("Expected clusterCount to be '%s' but was '%s'", expected, actual.getClusterCount());
		}
		return this;
	}

	public DominantColorOptionsAssert hasMaxSamples(int expected) {
		isNotNull();
		if (actual.getMaxSamples() != expected) {
			failWithMessage("Expected maxSamples to be '%s' but was '%s'", expected, actual.getMaxSamples());
		}
		return this;
	}

	public DominantColorOptionsAssert hasSeed(long expected) {
		isNotNull();
		if (actual.getSeed() != expected) {
			failWithMessage("Expected seed to be '%s' but was '%s'", expected, actual.getSeed());
		}
		return this;
	}

	public DominantColorOptionsAssert hasAlphaThreshold(int expected) {
		isNotNull();
		if (actual.getAlphaThreshold() != expected) {
			failWithMessage("Expected alphaThreshold to be '%s' but was '%s'", expected, actual.getAlphaThreshold());
		}
		return this;
	}

	public DominantColorOptionsAssert hasAchromaticChroma(double expected) {
		isNotNull();
		if (actual.getAchromaticChroma() != expected) {
			failWithMessage("Expected achromaticChroma to be '%s' but was '%s'", expected, actual.getAchromaticChroma());
		}
		return this;
	}

	public DominantColorOptionsAssert hasStaticRegion() {
		isNotNull();
		if (!actual.hasStaticRegion()) {
			failWithMessage("Expected a static region to be configured but regionW/regionH were %s/%s",
				actual.getRegionW(), actual.getRegionH());
		}
		return this;
	}

	public DominantColorOptionsAssert isValid() {
		isNotNull();
		if (!actual.validate().isValid()) {
			failWithMessage("Expected options to be valid but got errors: %s", actual.validate().getErrors());
		}
		return this;
	}

	public DominantColorOptionsAssert isInvalidBecauseOf(String fragment) {
		isNotNull();
		if (actual.validate().isValid()) {
			failWithMessage("Expected options to be invalid because of '%s' but they validated cleanly", fragment);
		}
		boolean matched = actual.validate().getErrors().stream().anyMatch(e -> e.contains(fragment));
		if (!matched) {
			failWithMessage("Expected a validation error mentioning '%s' but got: %s", fragment, actual.validate().getErrors());
		}
		return this;
	}
}
