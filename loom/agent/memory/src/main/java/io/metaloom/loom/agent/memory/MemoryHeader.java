package io.metaloom.loom.agent.memory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.metaloom.loom.db.model.memory.MemoryEntry;

/**
 * Renders the YAML frontmatter of a memory note, and strips any frontmatter the model tried to supply.
 *
 * <p>The header is never stored — it is regenerated from the {@code memory_entry} columns every time a note is read or written out as a file. That makes it
 * impossible for the header to drift from the row, and impossible for the model to forge provenance by writing its own.</p>
 *
 * <p>Emitting is hand-rolled on purpose: the schema is fixed and flat, so a YAML dependency would buy nothing and add a parser to the attack surface.</p>
 */
public final class MemoryHeader {

	/** How far into a document a closing fence is searched for before giving up. */
	private static final int MAX_FRONTMATTER_LINES = 40;

	/** Titles are single-line and bounded — they end up inside a quoted YAML scalar. */
	public static final int MAX_TITLE_LENGTH = 120;

	private MemoryHeader() {
	}

	/**
	 * Render the frontmatter block for an entry, including the trailing {@code ---} and a blank line.
	 *
	 * @param authorName
	 *            Resolved name of the last editor, may be null
	 */
	public static String render(MemoryEntry entry, String authorName) {
		StringBuilder sb = new StringBuilder(256);
		sb.append("---\n");
		field(sb, "id", entry.getMemoryId());
		field(sb, "scope", entry.getScope() == null ? null : entry.getScope().key());
		field(sb, "title", entry.getTitle());
		sb.append("version: ").append(entry.getVersion()).append('\n');
		field(sb, "created", format(entry.getCreated()));
		field(sb, "updated", format(entry.getEdited()));
		field(sb, "updatedBy", authorName);
		field(sb, "session", entry.getSessionName());
		field(sb, "chatUuid", entry.getChatUuid() == null ? null : entry.getChatUuid().toString());
		sb.append("---\n\n");
		return sb.toString();
	}

	/**
	 * The complete file as materialized into a session container: frontmatter followed by the body.
	 */
	public static String renderFile(MemoryEntry entry, String authorName) {
		String body = entry.getBody() == null ? "" : entry.getBody();
		return render(entry, authorName) + body;
	}

	/**
	 * A one-line provenance prefix handed to the model together with a note's body.
	 */
	public static String provenanceLine(MemoryEntry entry, String authorName) {
		StringBuilder sb = new StringBuilder();
		sb.append("[memory ").append(entry.getScope().key()).append(':').append(entry.getMemoryId());
		sb.append(" — v").append(entry.getVersion());
		if (entry.getEdited() != null) {
			sb.append(", updated ").append(format(entry.getEdited()));
		}
		if (authorName != null && !authorName.isBlank()) {
			sb.append(" by ").append(authorName);
		}
		if (entry.getSessionName() != null && !entry.getSessionName().isBlank()) {
			sb.append(" in session \"").append(sanitize(entry.getSessionName())).append('"');
		}
		sb.append(']');
		return sb.toString();
	}

	/**
	 * Remove a leading frontmatter block, if present.
	 *
	 * <p>Called on every write: the model is asked for the body only, so any {@code ---} block it produced is a forgery attempt (or confusion) and is
	 * discarded rather than merged. When there is no closing fence within {@link #MAX_FRONTMATTER_LINES} lines the input is returned unchanged — a
	 * document that merely starts with a horizontal rule must not be truncated.</p>
	 *
	 * @return the body without frontmatter
	 */
	public static String stripFrontmatter(String content) {
		if (content == null) {
			return "";
		}
		String normalized = content.stripLeading();
		if (!normalized.startsWith("---")) {
			return content;
		}
		String[] lines = normalized.split("\n", -1);
		if (!lines[0].strip().equals("---")) {
			return content;
		}
		int limit = Math.min(lines.length, MAX_FRONTMATTER_LINES);
		for (int i = 1; i < limit; i++) {
			if (lines[i].strip().equals("---")) {
				StringBuilder rest = new StringBuilder();
				for (int j = i + 1; j < lines.length; j++) {
					if (rest.length() > 0) {
						rest.append('\n');
					}
					rest.append(lines[j]);
				}
				return rest.toString().stripLeading();
			}
		}
		return content;
	}

	/**
	 * Whether the content carries a frontmatter block which {@link #stripFrontmatter(String)} would remove.
	 */
	public static boolean hasFrontmatter(String content) {
		return content != null && !stripFrontmatter(content).equals(content);
	}

	/**
	 * Reduce a title to a single bounded line so it can be emitted as a quoted YAML scalar.
	 */
	public static String sanitizeTitle(String title) {
		if (title == null) {
			return null;
		}
		String single = sanitize(title);
		if (single.isBlank()) {
			return null;
		}
		return single.length() > MAX_TITLE_LENGTH ? single.substring(0, MAX_TITLE_LENGTH) : single;
	}

	private static void field(StringBuilder sb, String key, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		sb.append(key).append(": \"").append(escape(value)).append("\"\n");
	}

	private static String escape(String value) {
		return sanitize(value).replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Collapse newlines and carriage returns — every rendered value must stay on one line.
	 */
	private static String sanitize(String value) {
		return value.replace("\r", " ").replace("\n", " ").strip();
	}

	private static String format(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
	}

}
