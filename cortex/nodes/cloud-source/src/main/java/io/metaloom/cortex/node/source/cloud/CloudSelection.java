package io.metaloom.cortex.node.source.cloud;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.fs.FileState;

/**
 * What one cloud source node instance is pointed at.
 *
 * <p>These are the options that <em>are</em> the work, so they come from the pipeline node
 * definition rather than the worker configuration. Credentials deliberately live elsewhere
 * ({@code CortexOptions.getGdrive()} / {@code getOnedrive()}), so a pipeline definition - stored in
 * Postgres and rendered in the editor - never carries a secret.</p>
 *
 * @param provider         the cloud this selection reads from
 * @param driveId          the resolved drive id
 * @param folderId         folder to scan, or null for the drive root
 * @param recursive        whether to descend into sub-folders
 * @param maxDepth         depth limit when recursing; 0 is unlimited
 * @param suffixes         lower-cased file suffixes to accept (without the dot); empty accepts anything
 * @param mimeTypes        lower-cased MIME prefixes to accept; empty accepts anything
 * @param emitStates       diff states emitted downstream
 * @param useDelta         prefer the provider's change feed over a full walk
 * @param includeTrashed   keep trashed/recycled items
 * @param exportNativeDocs export Google native documents rather than skipping them
 */
public record CloudSelection(
	CloudProviderId provider,
	String driveId,
	String folderId,
	boolean recursive,
	int maxDepth,
	Set<String> suffixes,
	Set<String> mimeTypes,
	Set<FileState> emitStates,
	boolean useDelta,
	boolean includeTrashed,
	boolean exportNativeDocs) {

	/**
	 * Newly discovered, changed and moved files.
	 *
	 * <p>Unlike {@code s3-source} this includes {@code MOVED}: a cloud file id is stable and the
	 * index records the parent, so a rename or a re-parent is genuinely distinguishable from a
	 * delete plus an add. This matches {@code filesystem-source}, which can do the same via
	 * inodes.</p>
	 */
	public static final Set<FileState> DEFAULT_EMIT_STATES =
		Set.of(FileState.NEW, FileState.MODIFIED, FileState.MOVED);

	public CloudSelection {
		if (provider == null) {
			throw new IllegalArgumentException("A cloud provider must be set");
		}
		if (driveId == null || driveId.isBlank()) {
			throw new IllegalArgumentException("A drive id must be configured");
		}
		folderId = folderId == null || folderId.isBlank() ? null : folderId.trim();
		maxDepth = Math.max(0, maxDepth);
		suffixes = suffixes == null ? Set.of() : Set.copyOf(suffixes);
		mimeTypes = mimeTypes == null ? Set.of() : Set.copyOf(mimeTypes);
		emitStates = emitStates == null || emitStates.isEmpty()
			? EnumSet.copyOf(DEFAULT_EMIT_STATES)
			: EnumSet.copyOf(emitStates);
	}

	/**
	 * Whether a file passes the content filters.
	 *
	 * <p>Folders are never "accepted" - they are structure, not media - but the walker still
	 * descends into them and the index still records them.</p>
	 *
	 * @param ref a file
	 * @return true when the file should be considered for emission
	 */
	public boolean accepts(CloudFileRef ref) {
		if (ref.folder()) {
			return false;
		}
		if (!includeTrashed && ref.trashed()) {
			return false;
		}
		return acceptsName(ref.name()) && acceptsMime(ref.mimeType());
	}

	/**
	 * @param name a file name
	 * @return whether it passes the suffix filter
	 */
	public boolean acceptsName(String name) {
		if (suffixes.isEmpty()) {
			return true;
		}
		if (name == null) {
			return false;
		}
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return false;
		}
		return suffixes.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/**
	 * Cloud items carry a real MIME type, which is a better filter than a file suffix - it catches
	 * an extension-less video and rejects a {@code .mp4} that is really something else.
	 *
	 * @param mimeType the provider's MIME type
	 * @return whether it passes the MIME filter
	 */
	public boolean acceptsMime(String mimeType) {
		if (mimeTypes.isEmpty()) {
			return true;
		}
		if (mimeType == null) {
			return false;
		}
		String normalized = mimeType.toLowerCase(Locale.ROOT);
		for (String prefix : mimeTypes) {
			if (normalized.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param state a diff state
	 * @return whether files in that state flow downstream
	 */
	public boolean emits(FileState state) {
		return emitStates.contains(state);
	}

	/**
	 * @param depth the depth of a folder below the selected root, where the root itself is 0
	 * @return whether the walker may descend into it
	 */
	public boolean mayDescend(int depth) {
		if (!recursive) {
			return false;
		}
		return maxDepth == 0 || depth < maxDepth;
	}

	/**
	 * Parse a comma-separated suffix list such as {@code "mp4, mkv,JPG"}.
	 *
	 * @param raw the raw value, may be null
	 * @return normalised suffixes without dots, lower-cased
	 */
	public static Set<String> parseSuffixes(String raw) {
		return parseCsv(raw, true);
	}

	/**
	 * Parse a comma-separated MIME prefix list such as {@code "video/, image/png"}.
	 *
	 * @param raw the raw value, may be null
	 * @return normalised, lower-cased MIME prefixes
	 */
	public static Set<String> parseMimeTypes(String raw) {
		return parseCsv(raw, false);
	}

	private static Set<String> parseCsv(String raw, boolean stripLeadingDot) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		Set<String> values = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String value = part.trim().toLowerCase(Locale.ROOT);
			if (stripLeadingDot && value.startsWith(".")) {
				value = value.substring(1);
			}
			if (!value.isBlank()) {
				values.add(value);
			}
		}
		return values;
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
