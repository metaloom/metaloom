package io.metaloom.cortex.node.source.s3;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.metaloom.fs.FileState;

/**
 * What one {@code s3-source} node instance is pointed at.
 *
 * <p>These are the options that <em>are</em> the work, so they come from the pipeline node
 * definition rather than the worker configuration. Connection settings and credentials
 * deliberately live elsewhere ({@code CortexOptions.getS3()}), so a pipeline definition - stored
 * in Postgres and rendered in the editor - never carries a secret.</p>
 *
 * @param bucket     bucket to scan
 * @param prefix     key prefix, may be null or empty for the whole bucket
 * @param suffixes   lower-cased file suffixes to accept (without the dot); empty accepts anything
 * @param emitStates diff states emitted downstream
 * @param startAfter resume listings after the last seen key
 * @param useEvents  prefer buffered bucket notifications over listing
 */
public record S3Selection(String bucket, String prefix, Set<String> suffixes,
	Set<FileState> emitStates, boolean startAfter, boolean useEvents) {

	/**
	 * Newly discovered and changed objects. {@code MOVED} is absent because S3 has no inode and
	 * the scanner never produces it.
	 */
	public static final Set<FileState> DEFAULT_EMIT_STATES = Set.of(FileState.NEW, FileState.MODIFIED);

	public S3Selection {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("An S3 bucket must be configured");
		}
		prefix = prefix == null || prefix.isBlank() ? null : prefix;
		suffixes = suffixes == null ? Set.of() : Set.copyOf(suffixes);
		emitStates = emitStates == null || emitStates.isEmpty()
			? EnumSet.copyOf(DEFAULT_EMIT_STATES)
			: EnumSet.copyOf(emitStates);
	}

	/**
	 * @param key an object key
	 * @return whether the key passes the suffix filter
	 */
	public boolean accepts(String key) {
		if (suffixes.isEmpty()) {
			return true;
		}
		int dot = key.lastIndexOf('.');
		if (dot < 0 || dot == key.length() - 1) {
			return false;
		}
		return suffixes.contains(key.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * @param state a diff state
	 * @return whether objects in that state flow downstream
	 */
	public boolean emits(FileState state) {
		return emitStates.contains(state);
	}

	/**
	 * Parse a comma-separated suffix list such as {@code "mp4, mkv,JPG"}.
	 *
	 * @param raw the raw value, may be null
	 * @return normalised suffixes without dots, lower-cased
	 */
	public static Set<String> parseSuffixes(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		Set<String> suffixes = new java.util.LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String value = part.trim().toLowerCase(Locale.ROOT);
			if (value.startsWith(".")) {
				value = value.substring(1);
			}
			if (!value.isBlank()) {
				suffixes.add(value);
			}
		}
		return suffixes;
	}

	/**
	 * Parse state names, ignoring blanks and unknown entries - options validation rejects those
	 * earlier with a better message.
	 *
	 * @param names raw state names
	 * @return the parsed states
	 */
	public static Set<FileState> parseStates(List<String> names) {
		Set<FileState> states = EnumSet.noneOf(FileState.class);
		if (names == null) {
			return states;
		}
		for (String name : names) {
			if (name == null || name.isBlank()) {
				continue;
			}
			try {
				states.add(FileState.valueOf(name.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				// Deliberately ignored; see javadoc.
			}
		}
		return states;
	}
}
