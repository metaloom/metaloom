package io.metaloom.cortex.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a document into pieces small enough to send to a model in one call.
 *
 * <p>
 * The split points are structural — paragraph first, then sentence — rather than a fixed window.
 * That matters for the same reason it matters to a human translator: a boundary drawn mid-sentence
 * hands the model half a clause with no subject, and it answers with half a clause back. Two such
 * halves rejoined do not make a sentence in the target language, and nothing downstream can repair
 * it. Packing whole paragraphs also keeps the model's view of the context as wide as the budget
 * allows, which is what a pronoun three sentences after its antecedent depends on.
 * </p>
 *
 * <p>
 * The hard split at the end is the honest fallback: a single sentence longer than the budget has no
 * safe boundary in it, so it is cut at the limit rather than dropped.
 * </p>
 */
public final class TextChunker {

	/** A blank line, i.e. any line terminator followed by whitespace and another line terminator. */
	private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\R[ \\t]*\\R");

	/** Whitespace that follows sentence-ending punctuation, including the ellipsis and its CJK cousins. */
	private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?…。！？])\\s+");

	/** What {@link #split(String, int)} joins paragraphs with, and what a caller should rejoin chunks with. */
	public static final String JOIN_SEPARATOR = "\n\n";

	private TextChunker() {
	}

	/**
	 * Split text into chunks of at most {@code maxChars} characters.
	 *
	 * @param text     the document; null or blank yields an empty list
	 * @param maxChars the per-chunk budget, must be positive
	 * @return the chunks in document order, never empty unless the text was
	 */
	public static List<String> split(String text, int maxChars) {
		if (maxChars < 1) {
			throw new IllegalArgumentException("maxChars must be at least 1, got " + maxChars);
		}
		List<String> chunks = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return chunks;
		}
		String trimmed = text.strip();
		if (trimmed.length() <= maxChars) {
			chunks.add(trimmed);
			return chunks;
		}

		StringBuilder current = new StringBuilder();
		for (String paragraph : PARAGRAPH_BREAK.split(trimmed)) {
			String para = paragraph.strip();
			if (para.isEmpty()) {
				continue;
			}
			if (para.length() > maxChars) {
				// The paragraph alone busts the budget: flush what we have and fall back to sentences.
				flush(chunks, current);
				for (String piece : splitParagraph(para, maxChars)) {
					append(chunks, current, piece, maxChars);
				}
			} else {
				append(chunks, current, para, maxChars);
			}
		}
		flush(chunks, current);
		return chunks;
	}

	/**
	 * Add one piece to the chunk being built, starting a new chunk when it no longer fits.
	 */
	private static void append(List<String> chunks, StringBuilder current, String piece, int maxChars) {
		if (current.isEmpty()) {
			current.append(piece);
			return;
		}
		if (current.length() + JOIN_SEPARATOR.length() + piece.length() <= maxChars) {
			current.append(JOIN_SEPARATOR).append(piece);
		} else {
			flush(chunks, current);
			current.append(piece);
		}
	}

	private static void flush(List<String> chunks, StringBuilder current) {
		if (!current.isEmpty()) {
			chunks.add(current.toString());
			current.setLength(0);
		}
	}

	/**
	 * Break an oversized paragraph on sentence boundaries, hard-cutting any sentence that is itself
	 * longer than the budget.
	 */
	private static List<String> splitParagraph(String paragraph, int maxChars) {
		List<String> pieces = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String sentence : SENTENCE_BREAK.split(paragraph)) {
			String trimmed = sentence.strip();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.length() > maxChars) {
				if (!current.isEmpty()) {
					pieces.add(current.toString());
					current.setLength(0);
				}
				for (int start = 0; start < trimmed.length(); start += maxChars) {
					pieces.add(trimmed.substring(start, Math.min(trimmed.length(), start + maxChars)));
				}
				continue;
			}
			if (current.isEmpty()) {
				current.append(trimmed);
			} else if (current.length() + 1 + trimmed.length() <= maxChars) {
				current.append(' ').append(trimmed);
			} else {
				pieces.add(current.toString());
				current.setLength(0);
				current.append(trimmed);
			}
		}
		if (!current.isEmpty()) {
			pieces.add(current.toString());
		}
		return pieces;
	}
}
