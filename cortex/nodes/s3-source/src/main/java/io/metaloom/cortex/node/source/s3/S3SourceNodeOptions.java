package io.metaloom.cortex.node.source.s3;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.fs.FileState;

/**
 * Defaults for the {@code s3-source} node, applied when a pipeline definition does not carry its
 * own values. Read from the {@code nodes} section of the Cortex configuration under the
 * {@value #KEY} key:
 *
 * <pre>
 * nodes:
 *   s3-source:
 *     enabled: true
 *     bucket: media
 *     prefix: 2026/
 *     suffixes: mp4,mkv,jpg
 *     emitStates:
 *       - NEW
 *       - MODIFIED
 *     startAfter: false
 *     useEvents: false
 * </pre>
 *
 * <p><b>No connection settings here.</b> Endpoint, region and credentials are worker-level
 * ({@code CortexOptions.getS3()}, {@code CORTEX_S3_*}), deliberately: these values can be set per
 * pipeline node in the editor, and the pipeline definition is stored in the database, so a secret
 * placed here would be persisted and displayed. It also means every worker resolving
 * {@code s3://} media is configured once, rather than per pipeline.</p>
 */
public class S3SourceNodeOptions extends AbstractNodeOptions<S3SourceNodeOptions> {

	public static final String KEY = "s3-source";

	/**
	 * Newly discovered and changed objects.
	 *
	 * <p>{@code MOVED} is absent on purpose - S3 has no inode, so the scanner cannot tell a rename
	 * from a delete plus an add and never emits that state.</p>
	 */
	public static final List<String> DEFAULT_EMIT_STATES = List.of(
		FileState.NEW.name(), FileState.MODIFIED.name());

	private String bucket;

	private String prefix;

	private String suffixes;

	private List<String> emitStates = new ArrayList<>(DEFAULT_EMIT_STATES);

	private boolean startAfter;

	private boolean useEvents;

	@Override
	protected S3SourceNodeOptions self() {
		return this;
	}

	/**
	 * Default bucket used when the pipeline definition supplies none.
	 */
	public String getBucket() {
		return bucket;
	}

	public S3SourceNodeOptions setBucket(String bucket) {
		this.bucket = bucket;
		return this;
	}

	/**
	 * Default key prefix. Empty or null scans the whole bucket.
	 */
	public String getPrefix() {
		return prefix;
	}

	public S3SourceNodeOptions setPrefix(String prefix) {
		this.prefix = prefix;
		return this;
	}

	/**
	 * Comma-separated file suffixes to accept, e.g. {@code mp4,mkv,jpg}. Empty accepts everything.
	 */
	public String getSuffixes() {
		return suffixes;
	}

	public S3SourceNodeOptions setSuffixes(String suffixes) {
		this.suffixes = suffixes;
		return this;
	}

	/**
	 * The {@link FileState} names emitted downstream.
	 */
	public List<String> getEmitStates() {
		return emitStates;
	}

	public S3SourceNodeOptions setEmitStates(List<String> emitStates) {
		this.emitStates = emitStates == null ? new ArrayList<>(DEFAULT_EMIT_STATES) : emitStates;
		return this;
	}

	/**
	 * Resume listings after the last key seen, instead of listing the selection from the start.
	 *
	 * <p>Only correct for append-only, lexicographically ordered key layouts (date-prefixed keys
	 * are the usual case). It cannot see edits to older keys, so the periodic reconcile listing
	 * still runs.</p>
	 */
	public boolean isStartAfter() {
		return startAfter;
	}

	public S3SourceNodeOptions setStartAfter(boolean startAfter) {
		this.startAfter = startAfter;
		return this;
	}

	/**
	 * Take changed keys from buffered bucket notifications instead of listing.
	 *
	 * <p>Requires the worker to have events enabled ({@code --s3-events-enabled}). Notifications
	 * are at-least-once and can be lost, so the reconcile interval still forces periodic full
	 * listings.</p>
	 */
	public boolean isUseEvents() {
		return useEvents;
	}

	public S3SourceNodeOptions setUseEvents(boolean useEvents) {
		this.useEvents = useEvents;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (bucket != null && bucket.contains("/")) {
			errors.add("bucket must be a bucket name, not a path: " + bucket);
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
