package io.metaloom.cortex.node.thumbnail.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link ThumbnailNodeOptions}.
 */
public class ThumbnailNodeOptionsAssert extends CortexNodeOptionsAssert {

	public ThumbnailNodeOptionsAssert(ThumbnailNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as ThumbnailNodeOptions.
	 */
	private ThumbnailNodeOptions thumbOptions() {
		return (ThumbnailNodeOptions) actual;
	}

	/**
	 * Assert that the tileSize is set to the expected value.
	 */
	public ThumbnailNodeOptionsAssert hasTileSize(int expectedSize) {
		isNotNull();
		if (thumbOptions().getTileSize() != expectedSize) {
			failWithMessage("Expected tileSize to be %d but was %d", expectedSize, thumbOptions().getTileSize());
		}
		return this;
	}

	/**
	 * Assert that the cols is set to the expected value.
	 */
	public ThumbnailNodeOptionsAssert hasCols(int expectedCols) {
		isNotNull();
		if (thumbOptions().getCols() != expectedCols) {
			failWithMessage("Expected cols to be %d but was %d", expectedCols, thumbOptions().getCols());
		}
		return this;
	}

	/**
	 * Assert that the rows is set to the expected value.
	 */
	public ThumbnailNodeOptionsAssert hasRows(int expectedRows) {
		isNotNull();
		if (thumbOptions().getRows() != expectedRows) {
			failWithMessage("Expected rows to be %d but was %d", expectedRows, thumbOptions().getRows());
		}
		return this;
	}
}