package io.metaloom.cortex.node.watermark.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.watermark.WatermarkNodeOptions;

/**
 * AssertJ assertions for {@link WatermarkNodeOptions}.
 */
public class WatermarkOptionsAssert extends AbstractCortexNodeOptionsAssert<WatermarkOptionsAssert, WatermarkNodeOptions> {

	public WatermarkOptionsAssert(WatermarkNodeOptions actual) {
		super(actual, WatermarkOptionsAssert.class);
	}

	public WatermarkOptionsAssert hasRelX(double expected) {
		isNotNull();
		if (actual.getRelX() != expected) {
			failWithMessage("Expected relX to be %s but was %s", expected, actual.getRelX());
		}
		return this;
	}

	public WatermarkOptionsAssert hasRelY(double expected) {
		isNotNull();
		if (actual.getRelY() != expected) {
			failWithMessage("Expected relY to be %s but was %s", expected, actual.getRelY());
		}
		return this;
	}

	public WatermarkOptionsAssert hasScale(double expected) {
		isNotNull();
		if (actual.getScale() != expected) {
			failWithMessage("Expected scale to be %s but was %s", expected, actual.getScale());
		}
		return this;
	}

	public WatermarkOptionsAssert hasOpacity(double expected) {
		isNotNull();
		if (actual.getOpacity() != expected) {
			failWithMessage("Expected opacity to be %s but was %s", expected, actual.getOpacity());
		}
		return this;
	}

	public WatermarkOptionsAssert hasVideoCodec(String expected) {
		isNotNull();
		if (!expected.equals(actual.getVideoCodec())) {
			failWithMessage("Expected videoCodec to be '%s' but was '%s'", expected, actual.getVideoCodec());
		}
		return this;
	}

	public WatermarkOptionsAssert hasVideoCrf(int expected) {
		isNotNull();
		if (actual.getVideoCrf() != expected) {
			failWithMessage("Expected videoCrf to be %d but was %d", expected, actual.getVideoCrf());
		}
		return this;
	}
}
