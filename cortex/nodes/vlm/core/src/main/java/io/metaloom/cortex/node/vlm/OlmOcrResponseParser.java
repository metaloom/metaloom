package io.metaloom.cortex.node.vlm;

import java.util.HashMap;
import java.util.Map;

/**
 * Splits an olmOCR reply into its YAML front matter and the transcribed page body.
 *
 * <p>
 * The parser is deliberately lenient - a vision model is not a YAML emitter. Anything it cannot make sense of falls back to "no front matter, the whole
 * reply is the page text", because losing the transcription over a malformed header would be the worse outcome. Unknown front matter keys are ignored, so
 * a newer model revision that adds a field does not break the node.
 * </p>
 */
public final class OlmOcrResponseParser {

	private static final String FENCE = "---";

	private OlmOcrResponseParser() {
	}

	/**
	 * Parse a raw model reply.
	 *
	 * @param raw the reply content, may be null
	 * @return the parsed response, never null
	 */
	public static OlmOcrResponse parse(String raw) {
		String content = raw == null ? "" : raw.strip();
		if (content.isEmpty()) {
			return new OlmOcrResponse(null, true, 0, false, false, "", false);
		}

		// Some servers wrap the whole answer in a markdown code fence - peel it off before looking for the front matter.
		content = stripCodeFence(content);

		if (!content.startsWith(FENCE)) {
			return new OlmOcrResponse(null, true, 0, false, false, content, false);
		}

		// Find the closing fence. It must sit on a line of its own, otherwise a "---" horizontal rule inside the page would cut the body short.
		int bodyStart = content.indexOf('\n');
		if (bodyStart < 0) {
			return new OlmOcrResponse(null, true, 0, false, false, content, false);
		}
		int closing = indexOfFenceLine(content, bodyStart + 1);
		if (closing < 0) {
			// An opening fence with no closing one - treat the whole thing as text rather than guessing where the header ends.
			return new OlmOcrResponse(null, true, 0, false, false, content, false);
		}

		Map<String, String> frontMatter = parseFrontMatter(content.substring(bodyStart + 1, closing));
		String body = content.substring(closing);
		// Skip past the closing fence line itself.
		int nl = body.indexOf('\n');
		body = nl < 0 ? "" : body.substring(nl + 1).strip();

		return new OlmOcrResponse(
			frontMatter.get("primary_language"),
			parseBoolean(frontMatter.get("is_rotation_valid"), true),
			parseRotation(frontMatter.get("rotation_correction")),
			parseBoolean(frontMatter.get("is_table"), false),
			parseBoolean(frontMatter.get("is_diagram"), false),
			body,
			false);
	}

	/**
	 * Index of the next line that consists of exactly {@code ---}, starting the search at {@code from}, or -1.
	 */
	private static int indexOfFenceLine(String content, int from) {
		int pos = from;
		while (pos <= content.length()) {
			int eol = content.indexOf('\n', pos);
			String line = (eol < 0 ? content.substring(pos) : content.substring(pos, eol)).strip();
			if (line.equals(FENCE)) {
				return pos;
			}
			if (eol < 0) {
				return -1;
			}
			pos = eol + 1;
		}
		return -1;
	}

	private static Map<String, String> parseFrontMatter(String block) {
		Map<String, String> values = new HashMap<>();
		for (String line : block.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int colon = trimmed.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String key = trimmed.substring(0, colon).strip().toLowerCase();
			String value = unquote(trimmed.substring(colon + 1).strip());
			values.put(key, value);
		}
		return values;
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	/**
	 * Peel a leading markdown code fence (```` ```yaml ```` / ```` ``` ````) plus its trailing counterpart.
	 */
	private static String stripCodeFence(String content) {
		if (!content.startsWith("```")) {
			return content;
		}
		int firstNl = content.indexOf('\n');
		if (firstNl < 0) {
			return content;
		}
		String inner = content.substring(firstNl + 1);
		int lastFence = inner.lastIndexOf("```");
		if (lastFence >= 0) {
			inner = inner.substring(0, lastFence);
		}
		return inner.strip();
	}

	/**
	 * Accepts the Python-flavoured {@code True}/{@code False} that olmOCR emits as well as the YAML/JSON spellings.
	 */
	private static boolean parseBoolean(String value, boolean fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return switch (value.strip().toLowerCase()) {
			case "true", "yes", "1" -> true;
			case "false", "no", "0" -> false;
			default -> fallback;
		};
	}

	/**
	 * Rotation is only meaningful as one of the four quarter turns; anything else is reported as 0 so the node never rotates by a nonsense angle.
	 */
	private static int parseRotation(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		try {
			int degrees = Integer.parseInt(value.strip());
			return switch (degrees) {
				case 90, 180, 270 -> degrees;
				default -> 0;
			};
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
