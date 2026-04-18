package io.metaloom.cortex.node.thumbnail;

import javax.inject.Inject;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class ThumbnailNodeOptions extends AbstractNodeOptions<ThumbnailNodeOptions> {

	private static final int DEFAULT_TILE_SIZE = 384;

	private static final int DEFAULT_COLS = 6;

	private static final int DEFAULT_ROWS = 1;

	public static final String KEY = "thumbnail";

	private int cols = DEFAULT_COLS;

	private int rows = DEFAULT_ROWS;

	private int tileSize = DEFAULT_TILE_SIZE;

	@Inject
	public ThumbnailNodeOptions() {
	}

	@Override
	protected ThumbnailNodeOptions self() {
		return this;
	}

	public int getTileSize() {
		return tileSize;
	}

	public int getCols() {
		return cols;
	}

	public ThumbnailNodeOptions setCols(int cols) {
		this.cols = cols;
		return this;
	}

	public int getRows() {
		return rows;
	}

	public ThumbnailNodeOptions setRows(int rows) {
		this.rows = rows;
		return this;
	}

}
