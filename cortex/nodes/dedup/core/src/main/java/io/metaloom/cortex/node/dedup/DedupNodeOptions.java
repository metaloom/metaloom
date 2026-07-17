package io.metaloom.cortex.node.dedup;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

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

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		
		// dupFolder must not be null
		if (dupFolder == null) {
			errors.add("dupFolder must not be null");
		}
		
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
