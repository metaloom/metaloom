package io.metaloom.cortex.node.source.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.fs.FileState;

/**
 * Configured defaults for a cloud source node, shared by both providers.
 *
 * <p><b>No connection settings here.</b> Credentials, endpoints and cache limits are worker-level
 * ({@code CortexOptions.getGdrive()} / {@code getOnedrive()}, {@code CORTEX_GDRIVE_*} /
 * {@code CORTEX_ONEDRIVE_*}), deliberately: these values can be set per pipeline node in the
 * editor, the pipeline definition is stored in the database, and {@code ParameterType} has no
 * {@code SECRET} - so a credential placed here would be persisted and displayed in clear.</p>
 *
 * @param <T> the concrete provider options type
 */
public abstract class CloudSourceNodeOptions<T extends CloudSourceNodeOptions<T>> extends AbstractNodeOptions<T> {

	/**
	 * Newly discovered, changed and moved files - matching {@code filesystem-source} rather than
	 * {@code s3-source}, because a cloud drive can genuinely detect a move.
	 */
	public static final List<String> DEFAULT_EMIT_STATES =
		List.of(FileState.NEW.name(), FileState.MODIFIED.name(), FileState.MOVED.name());

	private String driveId;
	private String folderId;
	private boolean recursive = true;
	private int maxDepth;
	private String suffixes;
	private String mimeTypes;
	private List<String> emitStates = new ArrayList<>(DEFAULT_EMIT_STATES);
	private boolean useDelta = true;
	private boolean includeTrashed;

	/**
	 * @return which cloud these options configure
	 */
	public abstract CloudProviderId provider();

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (maxDepth < 0) {
			errors.add("maxDepth must be non-negative, got " + maxDepth);
		}
		if (driveId != null && driveId.contains("/")) {
			errors.add("driveId must be an id, not a path: " + driveId);
		}
		if (folderId != null && folderId.contains("/")) {
			errors.add("folderId must be an id, not a path: " + folderId);
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
		validateProviderSpecific(errors);

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}

	/**
	 * Hook for options that only make sense on one provider.
	 *
	 * @param errors collected errors, to append to
	 */
	protected void validateProviderSpecific(List<String> errors) {
	}

	private static boolean isValidState(String state) {
		try {
			FileState.valueOf(state.trim().toUpperCase(Locale.ROOT));
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * @return the drive to read from, or null to use the worker's configured default
	 */
	public String getDriveId() {
		return driveId;
	}

	public T setDriveId(String driveId) {
		this.driveId = driveId;
		return self();
	}

	/**
	 * @return the folder to scan, or null for the drive root
	 */
	public String getFolderId() {
		return folderId;
	}

	public T setFolderId(String folderId) {
		this.folderId = folderId;
		return self();
	}

	public boolean isRecursive() {
		return recursive;
	}

	public T setRecursive(boolean recursive) {
		this.recursive = recursive;
		return self();
	}

	/**
	 * @return how deep to descend; 0 is unlimited
	 */
	public int getMaxDepth() {
		return maxDepth;
	}

	public T setMaxDepth(int maxDepth) {
		this.maxDepth = maxDepth;
		return self();
	}

	/**
	 * @return comma-separated file suffixes to accept, e.g. {@code mp4,mkv,jpg}
	 */
	public String getSuffixes() {
		return suffixes;
	}

	public T setSuffixes(String suffixes) {
		this.suffixes = suffixes;
		return self();
	}

	/**
	 * @return comma-separated MIME prefixes to accept, e.g. {@code video/,image/}
	 */
	public String getMimeTypes() {
		return mimeTypes;
	}

	public T setMimeTypes(String mimeTypes) {
		this.mimeTypes = mimeTypes;
		return self();
	}

	public List<String> getEmitStates() {
		return emitStates;
	}

	public T setEmitStates(List<String> emitStates) {
		this.emitStates = emitStates == null ? new ArrayList<>(DEFAULT_EMIT_STATES) : emitStates;
		return self();
	}

	/**
	 * @return whether to use the provider's change feed instead of walking the folder tree
	 */
	public boolean isUseDelta() {
		return useDelta;
	}

	public T setUseDelta(boolean useDelta) {
		this.useDelta = useDelta;
		return self();
	}

	public boolean isIncludeTrashed() {
		return includeTrashed;
	}

	public T setIncludeTrashed(boolean includeTrashed) {
		this.includeTrashed = includeTrashed;
		return self();
	}
}
