package io.metaloom.cortex.node.source.fs;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.fs.FileState;

/**
 * Configuration for the {@code filesystem-source} node.
 *
 * <p>These are the defaults applied when a pipeline definition does not specify
 * its own {@code path} / {@code pathGlobs} / {@code emitStates}. They are read
 * from the {@code nodes} section of the Cortex configuration under the
 * {@value #KEY} key:</p>
 *
 * <pre>
 * nodes:
 *   filesystem-source:
 *     enabled: true
 *     path: /media/library
 *     pathGlobs:
 *       - "/media/library/**&#47;*.mp4"
 *     emitStates:
 *       - NEW
 *       - MODIFIED
 *       - MOVED
 *     indexPath: /var/lib/cortex/filesystem-index
 * </pre>
 *
 * <p>When a single {@code path} (root) is configured the node performs a
 * <em>differential</em> scan backed by a persisted, per-root index and only
 * emits files whose {@link FileState} is contained in {@code emitStates}. Glob
 * selections always fall back to a full re-walk.</p>
 */
public class FilesystemSourceNodeOptions extends AbstractNodeOptions<FilesystemSourceNodeOptions> {

	public static final String KEY = "filesystem-source";

	/**
	 * Default set of states emitted downstream: newly discovered, modified and moved files.
	 */
	public static final List<String> DEFAULT_EMIT_STATES = List.of(
		FileState.NEW.name(), FileState.MODIFIED.name(), FileState.MOVED.name());

	private String path;

	private List<String> pathGlobs = new ArrayList<>();

	private List<String> emitStates = new ArrayList<>(DEFAULT_EMIT_STATES);

	private String indexPath;

	@Override
	protected FilesystemSourceNodeOptions self() {
		return this;
	}

	/**
	 * Default root directory scanned when the pipeline definition supplies no path.
	 */
	public String getPath() {
		return path;
	}

	public FilesystemSourceNodeOptions setPath(String path) {
		this.path = path;
		return this;
	}

	/**
	 * Default path globs used when the pipeline definition supplies neither
	 * {@code path} nor {@code pathGlobs}. Takes precedence over {@link #getPath()}.
	 */
	public List<String> getPathGlobs() {
		return pathGlobs;
	}

	public FilesystemSourceNodeOptions setPathGlobs(List<String> pathGlobs) {
		this.pathGlobs = pathGlobs == null ? new ArrayList<>() : pathGlobs;
		return this;
	}

	/**
	 * The {@link FileState} names that are emitted downstream during a differential (root) scan. Only relevant in root mode; glob mode always emits every match.
	 */
	public List<String> getEmitStates() {
		return emitStates;
	}

	public FilesystemSourceNodeOptions setEmitStates(List<String> emitStates) {
		this.emitStates = emitStates == null ? new ArrayList<>(DEFAULT_EMIT_STATES) : emitStates;
		return this;
	}

	/**
	 * Optional local base directory for the persisted per-root indexes. When unset the node derives it from {@code CortexOptions.getMetaPath()}.
	 */
	public String getIndexPath() {
		return indexPath;
	}

	public FilesystemSourceNodeOptions setIndexPath(String indexPath) {
		this.indexPath = indexPath;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (pathGlobs != null) {
			for (String glob : pathGlobs) {
				if (glob == null || glob.isBlank()) {
					errors.add("pathGlobs must not contain blank entries");
					break;
				}
			}
		}

		if (emitStates != null) {
			for (String state : emitStates) {
				if (state == null || !isValidState(state)) {
					errors.add("emitStates contains an unknown file state: " + state
						+ " (allowed: NEW, MODIFIED, MOVED, PRESENT, DELETED)");
					break;
				}
			}
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}

	private static boolean isValidState(String state) {
		try {
			FileState.valueOf(state.trim().toUpperCase());
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
