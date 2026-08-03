package io.metaloom.cortex.node.dedup;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options shared by {@code hash-dedup} and {@code fingerprint-dedup-apply}, which are configured
 * under the {@code dedup} and {@code fingerprint-dedup-apply} keys respectively.
 *
 * <p>
 * The {@link ParamDoc} below carries {@code hash-dedup}'s prose; {@code fingerprint-dedup-apply}
 * words the same parameter differently and re-documents it with a {@code ParamOverride} on its own
 * {@code NodeSpec}.
 * </p>
 */
public class DedupNodeOptions extends AbstractNodeOptions<DedupNodeOptions> {

	private static final String DEFAULT_DUP_FOLDER = "duplicates";

	@ParamDoc(label = "Duplicates Folder", description = "Target folder for duplicate files")
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
