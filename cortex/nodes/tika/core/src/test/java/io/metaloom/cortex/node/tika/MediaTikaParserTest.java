package io.metaloom.cortex.node.tika;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;

/**
 * The parser that produces the {@code content} port.
 *
 * <p>
 * 🔴 It returned {@code null} for every file until this test existed, printing the metadata names to {@code System.out} instead - so the node reported
 * DONE, wrote nothing to the port and persisted an empty component for every document in the library. The text is the whole output of this node, and
 * these are the cases that say so.
 * </p>
 *
 * <p>
 * Every fixture is written here rather than read from the corpus: the corpus is unversioned, and "a document with 200,000 characters" is a property of
 * the test, not of any file that happens to be on this machine.
 * </p>
 */
class MediaTikaParserTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private StubLoomMedia document(String name, String content) throws Exception {
		Path file = tempDir.toPath().resolve(name);
		Files.writeString(file, content, UTF_8);
		StubLoomMedia media = new StubLoomMedia(file.toAbsolutePath().toString(), false, false, false, true);
		media.setSHA512(HASH);
		return media;
	}

	@Test
	void testTheDocumentTextComesBack() throws Exception {
		String text = MediaTikaParser.parse(document("notes.txt", "The quick brown fox jumps over the lazy dog."));

		assertThat(text).isEqualTo("The quick brown fox jumps over the lazy dog.");
	}

	@Test
	void testMarkupIsStrippedAndTheDeclaredCharsetIsHonoured() throws Exception {
		StubLoomMedia media = document("page.html",
			"<html><head><meta charset=\"utf-8\"><title>Ignored</title></head><body><p>Grüße aus München</p></body></html>");

		String text = MediaTikaParser.parse(media);

		assertThat(text).contains("Grüße aus München");
		assertThat(text).doesNotContain("<p>").doesNotContain("</body>");
	}

	/**
	 * A file that carries no text answers null rather than {@code ""}, so the node can leave the port unwritten instead of announcing an empty document
	 * to everything downstream.
	 */
	@Test
	void testAnEmptyDocumentAnswersNull() throws Exception {
		assertThat(MediaTikaParser.parse(document("empty.txt", "   \n\t\n"))).isNull();
	}

	/**
	 * 🔴 Tika's default {@link org.apache.tika.sax.BodyContentHandler} limit is 100,000 characters and it signals the limit by throwing, so every longer
	 * document failed outright - the common case for a PDF report.
	 */
	@Test
	void testADocumentLongerThanTikasDefaultLimitIsExtractedInFull() throws Exception {
		String body = "sentence ".repeat(30_000);
		assertThat(body.length()).isGreaterThan(100_000);

		String text = MediaTikaParser.parse(document("long.txt", body));

		assertThat(text).hasSize(body.trim().length());
	}

	/**
	 * Past the node's own limit the text is cut, not lost: a truncation is still a result, and failing the item would keep nothing at all.
	 */
	@Test
	void testAnOversizedDocumentIsTruncatedRatherThanFailed() throws Exception {
		String body = "x".repeat(MediaTikaParser.MAX_CONTENT_CHARS + 50_000);

		String text = MediaTikaParser.parse(document("huge.txt", body));

		assertThat(text).isNotNull();
		assertThat(text.length())
			.isLessThanOrEqualTo(MediaTikaParser.MAX_CONTENT_CHARS)
			.isGreaterThan(MediaTikaParser.MAX_CONTENT_CHARS / 2);
	}
}
