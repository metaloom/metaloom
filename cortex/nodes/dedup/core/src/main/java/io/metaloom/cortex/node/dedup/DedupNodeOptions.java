package io.metaloom.cortex.node.dedup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options shared by {@code hash-dedup} and {@code fingerprint-dedup-apply}, which are configured under the {@code dedup} and
 * {@code fingerprint-dedup-apply} keys respectively.
 *
 * <p>
 * 🔴 <b>{@code dupFolder} is gone.</b> Both nodes used to move files into it themselves; they now report their findings on output ports and a
 * downstream {@code move} node does the relocating, so the destination, the conflict policy and whether the original survives are properties of the
 * pipeline rather than of the worker's YAML.
 * </p>
 *
 * <p>
 * ⚠️ {@code CortexOptionsLoader} sets {@code FAIL_ON_UNKNOWN_PROPERTIES = false}, so a {@code cortex.yml} that still carries {@code dupFolder} keeps
 * a worker booting - but the value is now <b>silently ignored</b>. That is the one real cost of removing it outright, and it belongs in the release
 * notes: an operator who does not re-wire their pipeline will find that duplicates stop being moved, with nothing in the log to say why.
 * </p>
 */
public class DedupNodeOptions extends AbstractNodeOptions<DedupNodeOptions> {

	/**
	 * Never act when the <b>keeper</b> itself lives under this folder.
	 *
	 * <p>
	 * The residue of {@code dupFolder}, and the one safeguard that could not move downstream with the move: it asks a question about the KEEP, and the
	 * move node only ever sees the duplicate. An operator who trashes duplicates into {@code /data/trash} should set this to the same folder, so a
	 * keeper that has itself already been trashed can never be used to justify discarding another file.
	 * </p>
	 *
	 * <p>
	 * Empty by default, i.e. off. Only {@code fingerprint-dedup-apply} reads it.
	 * </p>
	 */
	@ParamDoc(label = "Keeper Exclude Folder",
		description = "Never act when the keeper itself lives under this folder. Leave empty to disable the check")
	private Path keepExcludeFolder;

	public Path getKeepExcludeFolder() {
		return keepExcludeFolder;
	}

	public DedupNodeOptions setKeepExcludeFolder(Path keepExcludeFolder) {
		this.keepExcludeFolder = keepExcludeFolder;
		return this;
	}

	@Override
	protected DedupNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());
		// keepExcludeFolder is optional: null means "do not apply the check at all", which is the default.
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
