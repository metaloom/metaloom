package io.metaloom.cortex.node.thumbnail.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;

/**
 * AssertJ assertions for {@link ThumbnailNodeOptions}.
 */
public class ThumbnailNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<ThumbnailNodeOptionsAssert, ThumbnailNodeOptions> {

	public ThumbnailNodeOptionsAssert(ThumbnailNodeOptions actual) {
		super(actual, ThumbnailNodeOptionsAssert.class);
	}

	/**
	 * Assert that the tileSize is set to the expected value.
	 */
	public ThumbnailNodeOptionsAssert hasTileSize(int expectedSize) {
		isNotNull();
		if (actual.getTileSize() != expectedSize) {
			failWithMessage("Expected tileSize to be %d but was %d", expectedSize, actual.getTileSize());
		}
		return this;
	}

	/**
	 * Assert that the cols is set to the expected value.
	 */
	public ThumbnailNodeOptionsAssert hasCols(int expectedCols) {
		isNotNull();
		if (actual.getCols() != expectedCols) {
			failWithMessage("Expected cols to be %d but was %d", expectedCols, actual.getCols());
		}
		return this;
	}

	/**
	 * Assert that the rows is set to the expected value.
	 */
	public ThumbnailNodeOptionsAssert hasRows(int expectedRows) {
		isNotNull();
		if (actual.getRows() != expectedRows) {
			failWithMessage("Expected rows to be %d but was %d", expectedRows, actual.getRows());
		}
		return this;
	}
}
