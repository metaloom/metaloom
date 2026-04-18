package io.metaloom.cortex.node.dedup;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class DedupNodeOptions extends AbstractNodeOptions<DedupNodeOptions> {

	private static final String DEFAULT_DUP_FOLDER = "duplicates";

	private Path dupFolder = Paths.get(DEFAULT_DUP_FOLDER);

	public Path getDupFolder() {
		return dupFolder;
	}

	public DedupNodeOptions setDupFolder(Path dupFolder) {
		this.dupFolder = dupFolder;
		return this;
	}

	@Override
	protected DedupNodeOptions self() {
		return this;
	}

}
