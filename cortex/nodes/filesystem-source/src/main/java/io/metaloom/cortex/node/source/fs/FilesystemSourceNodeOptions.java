package io.metaloom.cortex.node.source.fs;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Configuration for the {@code filesystem-source} node.
 *
 * <p>These are the defaults applied when a pipeline definition does not specify
 * its own {@code path} / {@code pathGlobs}. They are read from the {@code nodes}
 * section of the Cortex configuration under the {@value #KEY} key:</p>
 *
 * <pre>
 * nodes:
 *   filesystem-source:
 *     enabled: true
 *     path: /media/library
 *     pathGlobs:
 *       - "/media/library/**&#47;*.mp4"
 *     maxDepth: 0
 * </pre>
 */
public class FilesystemSourceNodeOptions extends AbstractNodeOptions<FilesystemSourceNodeOptions> {

	public static final String KEY = "filesystem-source";

	private String path;

	private List<String> pathGlobs = new ArrayList<>();

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

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
