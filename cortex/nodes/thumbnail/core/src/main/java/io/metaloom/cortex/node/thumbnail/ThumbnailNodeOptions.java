package io.metaloom.cortex.node.thumbnail;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class ThumbnailNodeOptions extends AbstractNodeOptions<ThumbnailNodeOptions> {

	private static final int DEFAULT_TILE_SIZE = 384;

	private static final int DEFAULT_COLS = 6;

	private static final int DEFAULT_ROWS = 1;

	public static final String KEY = "thumbnail";

	@ParamDoc(label = "Grid Columns", min = "1", max = "20")
	private int cols = DEFAULT_COLS;

	@ParamDoc(label = "Grid Rows", min = "1", max = "20")
	private int rows = DEFAULT_ROWS;

	@ParamDoc(label = "Tile Size (px)", min = "32", max = "1024")
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

	public ThumbnailNodeOptions setTileSize(int tileSize) {
		this.tileSize = tileSize;
		return this;
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

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// tileSize must be positive
		if (tileSize <= 0) {
			errors.add("tileSize must be positive, got " + tileSize);
		}
		
		// cols must be positive
		if (cols <= 0) {
			errors.add("cols must be positive, got " + cols);
		}
		
		// rows must be positive
		if (rows <= 0) {
			errors.add("rows must be positive, got " + rows);
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
