package io.metaloom.cortex.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The chunker's contract is "never exceed the budget, and never split where a translator could not
 * recover" - so every test here checks both the size bound and where the seam landed.
 */
class TextChunkerTest {

	@Test
	void testShortTextStaysWhole() {
		List<String> chunks = TextChunker.split("A short sentence.", 100);
		assertThat(chunks).containsExactly("A short sentence.");
	}

	@Test
	void testBlankInputYieldsNothing() {
		assertThat(TextChunker.split(null, 100)).isEmpty();
		assertThat(TextChunker.split("", 100)).isEmpty();
		assertThat(TextChunker.split("   \n\n  ", 100)).isEmpty();
	}

	@Test
	void testParagraphsArePackedGreedily() {
		String text = "aaaa\n\nbbbb\n\ncccc\n\ndddd";
		// Budget fits two 4-char paragraphs plus the two-character separator, but not three.
		List<String> chunks = TextChunker.split(text, 10);

		assertThat(chunks).containsExactly("aaaa\n\nbbbb", "cccc\n\ndddd");
	}

	@Test
	void testParagraphBoundaryIsPreferredOverFillingTheBudget() {
		String text = "one\n\n" + "x".repeat(40);
		List<String> chunks = TextChunker.split(text, 50);

		// "one" plus the long paragraph is 45 chars and would fit, but the long one is added whole.
		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0)).startsWith("one\n\n");
	}

	@Test
	void testOversizedParagraphSplitsOnSentences() {
		String text = "First sentence here. Second sentence here. Third sentence here.";
		List<String> chunks = TextChunker.split(text, 45);

		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(45));
		// Every chunk ends on sentence-ending punctuation - no clause was cut in half.
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).endsWith("."));
		assertThat(String.join(" ", chunks)).isEqualTo(text);
	}

	@Test
	void testQuestionAndExclamationAreSentenceBoundaries() {
		String text = "Who goes there? Nobody at all! Then we may proceed.";
		List<String> chunks = TextChunker.split(text, 32);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).matches(".*[.?!]$"));
	}

	@Test
	void testOversizedSentenceIsHardSplit() {
		// No boundary exists inside it, so cutting at the limit is the only option left.
		String text = "x".repeat(250);
		List<String> chunks = TextChunker.split(text, 100);

		assertThat(chunks).hasSize(3);
		assertThat(chunks.get(0)).hasSize(100);
		assertThat(chunks.get(1)).hasSize(100);
		assertThat(chunks.get(2)).hasSize(50);
		assertThat(String.join("", chunks)).isEqualTo(text);
	}

	@Test
	void testNoChunkEverExceedsTheBudget() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			text.append("Paragraph ").append(i).append(" says something worth translating. ")
				.append("It also says a second thing.\n\n");
		}
		List<String> chunks = TextChunker.split(text.toString(), 500);

		assertThat(chunks).hasSizeGreaterThan(5);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(500));
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
	}

	@Test
	void testBlankParagraphsAreDropped() {
		List<String> chunks = TextChunker.split("one\n\n\n\n   \n\ntwo\n\n\n\nthree", 8);

		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
		assertThat(String.join(" ", chunks)).doesNotContain("\n\n\n");
	}

	@Test
	void testRoundTripPreservesEveryWord() {
		String text = "Erster Absatz mit Text.\n\nZweiter Absatz mit mehr Text.\n\nDritter Absatz zum Schluss.";
		List<String> chunks = TextChunker.split(text, 30);

		String rejoined = String.join(TextChunker.JOIN_SEPARATOR, chunks);
		assertThat(rejoined.replaceAll("\\s+", " ")).isEqualTo(text.replaceAll("\\s+", " "));
	}

	@Test
	void testNonPositiveBudgetIsRejected() {
		assertThatThrownBy(() -> TextChunker.split("text", 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxChars must be at least 1");
	}
}
