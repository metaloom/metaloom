package io.metaloom.loom.agent.memory;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Validation of a memory id — the path-like address of one note within a scope, e.g. {@code projects/loom-db.md}.
 *
 * <p>The id is stored as a plain column value, but it also becomes a real filesystem path when memory is materialized into a session container. It is
 * therefore validated against a strict whitelist here, on the way in, and re-validated by the runner daemon on the way out. Anything that is not obviously
 * a safe relative markdown path is rejected with a message the model can act on.</p>
 */
public final class MemoryId {

	/** Upper bound on the whole id. */
	public static final int MAX_LENGTH = 200;

	/** Every path segment must look like this: lowercase, starts alphanumeric, no spaces. */
	private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

	/** Characters which are either shell/path metacharacters or filesystem-hostile. */
	private static final Pattern FORBIDDEN = Pattern.compile("[\\\\:~*?\"<>|\\p{Cntrl}]");

	private MemoryId() {
	}

	/**
	 * Normalize and validate an id.
	 *
	 * @param raw
	 *            The id as supplied by the model, the REST client or a test
	 * @param maxDepth
	 *            Maximum number of path segments
	 * @return the normalized (lowercased, trimmed) id
	 * @throws MemoryException
	 *             when the id is not a safe relative markdown path
	 */
	public static String parse(String raw, int maxDepth) {
		if (raw == null || raw.isBlank()) {
			throw new MemoryException("A memory id must be provided, e.g. 'notes.md' or 'projects/loom-db.md'.");
		}
		String id = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC).toLowerCase();
		if (id.startsWith("./")) {
			id = id.substring(2);
		}
		if (id.length() > MAX_LENGTH) {
			throw new MemoryException("The memory id must not be longer than " + MAX_LENGTH + " characters.");
		}
		if (id.startsWith("/")) {
			throw new MemoryException("The memory id must be relative, not absolute: " + raw);
		}
		if (id.endsWith("/")) {
			throw new MemoryException("The memory id must name a file, not a directory: " + raw);
		}
		if (id.contains("//")) {
			throw new MemoryException("The memory id must not contain empty path segments: " + raw);
		}
		if (FORBIDDEN.matcher(id).find()) {
			throw new MemoryException("The memory id contains forbidden characters: " + raw);
		}
		if (!id.chars().allMatch(c -> c < 128)) {
			throw new MemoryException("The memory id must be ASCII only: " + raw);
		}

		String[] segments = id.split("/");
		if (segments.length > maxDepth) {
			throw new MemoryException("The memory id must not be nested deeper than " + maxDepth + " levels: " + raw);
		}
		for (String segment : segments) {
			if (".".equals(segment) || "..".equals(segment)) {
				throw new MemoryException("The memory id must not contain '.' or '..' segments: " + raw);
			}
			if (!SEGMENT.matcher(segment).matches()) {
				throw new MemoryException(
					"Invalid segment '" + segment + "' in memory id: use lowercase letters, digits, '.', '_' and '-' only, starting with a letter or digit.");
			}
		}

		String fileName = segments[segments.length - 1];
		if (!fileName.endsWith(".md")) {
			throw new MemoryException("A memory id must end in '.md': " + raw);
		}
		if (fileName.length() <= 3) {
			throw new MemoryException("A memory id needs a file name before the '.md' suffix: " + raw);
		}
		return id;
	}

	/**
	 * Derive a human readable default title from an id, used when the caller supplies none.
	 * {@code projects/loom-db.md} becomes {@code loom-db}.
	 */
	public static String defaultTitle(String id) {
		String fileName = id.substring(id.lastIndexOf('/') + 1);
		return fileName.substring(0, fileName.length() - ".md".length());
	}

}
