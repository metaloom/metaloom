package io.metaloom.loom.agent.chat.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.mcp.attachment.PlainTextExtractor;

/**
 * The {@code <attachments>} block.
 *
 * <p>
 * Worth pinning because it is the one part of this feature that is paid for on <em>every</em> turn.
 * The tests below are about size and about honesty: the block must stay a fixed, small cost, and it
 * must not describe a file as something the tools will then refuse to treat it as.
 * </p>
 */
public class AttachmentPromptBuilderTest {

	private static final PlainTextExtractor EXTRACTOR = new PlainTextExtractor();

	private static Attachment attachment(String filename, String mimeType, long size) {
		Attachment attachment = mock(Attachment.class);
		when(attachment.getUuid()).thenReturn(UUID.nameUUIDFromBytes(filename.getBytes()));
		when(attachment.getFilename()).thenReturn(filename);
		when(attachment.getMimeType()).thenReturn(mimeType);
		when(attachment.getSize()).thenReturn(size);
		return attachment;
	}

	@Test
	public void testNoAttachmentsProduceNoBlockAtAll() {
		// Not an empty <attachments/> section: a chat with no files has to cost exactly what it cost
		// before this feature existed, and an empty block also invites the model to discuss
		// attachments nobody mentioned.
		assertEquals("", AttachmentPromptBuilder.build(List.of(), EXTRACTOR, 10));
		assertEquals("", AttachmentPromptBuilder.build(null, EXTRACTOR, 10));
	}

	@Test
	public void testOneLinePerFile() {
		String block = AttachmentPromptBuilder.build(
			List.of(attachment("brief.md", "text/markdown", 4096), attachment("hero.jpg", "image/jpeg", 1_258_291)),
			EXTRACTOR, 10);

		assertTrue(block.contains("<attachments>"), block);
		assertTrue(block.contains("</attachments>"), block);
		assertTrue(block.contains("brief.md · text/markdown · 4 KB"), block);
		assertTrue(block.contains("hero.jpg · image/jpeg · 1.2 MB"), block);
	}

	@Test
	public void testTheBlockCarriesTheIdTheToolsTake() {
		Attachment brief = attachment("brief.md", "text/markdown", 10);
		String block = AttachmentPromptBuilder.build(List.of(brief), EXTRACTOR, 10);

		// The uuid is the whole handle: read_attachment resolves it, and generate_image accepts it in
		// assetIds. A manifest that named the file but not the id would be unusable.
		assertTrue(block.contains(brief.getUuid().toString()), block);
	}

	@Test
	public void testATextFileIsAdvertisedAsReadable() {
		String block = AttachmentPromptBuilder.build(List.of(attachment("notes.txt", "text/plain", 10)), EXTRACTOR, 10);

		assertTrue(block.contains("readable as text"), block);
	}

	@Test
	public void testAnImageIsPointedAtTheImageTool() {
		String block = AttachmentPromptBuilder.build(List.of(attachment("hero.jpg", "image/jpeg", 10)), EXTRACTOR, 10);

		// Without this the model apologises that it cannot see pictures, which is true and useless.
		assertTrue(block.contains("image, for generate_image"), block);
	}

	@Test
	public void testAnUnreadableTypeSaysSoRatherThanPromising() {
		String block = AttachmentPromptBuilder.build(List.of(attachment("contract.pdf", "application/pdf", 10)), EXTRACTOR, 10);

		assertTrue(block.contains("cannot be read in this deployment"), block);
		assertFalse(block.contains("contract.pdf · application/pdf · 10 B · readable"), block);
	}

	@Test
	public void testTheManifestAgreesWithTheExtractorItWasGiven() {
		// The same extractor instance the read_attachment tool holds. If the two ever disagreed, the
		// manifest would advertise a file the tool then refuses - the failure this parameter exists
		// to make impossible.
		Attachment json = attachment("payload.json", "application/json", 10);

		String block = AttachmentPromptBuilder.build(List.of(json), EXTRACTOR, 10);

		assertEquals(EXTRACTOR.supports("application/json"), block.contains("readable as text"));
	}

	@Test
	public void testTheListIsCappedAndSaysSo() {
		List<Attachment> many = List.of(
			attachment("a.txt", "text/plain", 1), attachment("b.txt", "text/plain", 1),
			attachment("c.txt", "text/plain", 1), attachment("d.txt", "text/plain", 1));

		String block = AttachmentPromptBuilder.build(many, EXTRACTOR, 2);

		assertTrue(block.contains("a.txt"), block);
		assertTrue(block.contains("b.txt"), block);
		assertFalse(block.contains("c.txt"), block);
		// Silently truncating would leave the model confidently unaware of files the user can see.
		assertTrue(block.contains("2 older attachment(s) are not listed"), block);
	}

	@Test
	public void testNothingIsListedWhenTheCapIsZero() {
		String block = AttachmentPromptBuilder.build(List.of(attachment("a.txt", "text/plain", 1)), EXTRACTOR, 0);

		assertEquals("", block);
	}

	@Test
	public void testTheBlockStatesThatContentsAreAbsent() {
		String block = AttachmentPromptBuilder.build(List.of(attachment("brief.md", "text/markdown", 10)), EXTRACTOR, 10);

		// The expensive failure this prevents: a model that treats a listed filename as a file it has
		// already read, and answers questions about it from the name.
		assertTrue(block.contains("contents are NOT in this conversation"), block);
		assertTrue(block.contains("read_attachment"), block);
	}

	@Test
	public void testAMissingMimeTypeDoesNotBreakTheLine() {
		String block = AttachmentPromptBuilder.build(List.of(attachment("mystery", null, 10)), EXTRACTOR, 10);

		assertTrue(block.contains("mystery · unknown type · 10 B"), block);
		assertTrue(block.contains("cannot be read in this deployment"), block);
	}

	@Test
	public void testTheCostPerFileStaysFlatAsTheFileGrows() {
		// The property the whole design rests on: a forty-megabyte file contributes the same line as
		// a four-kilobyte one, give or take the digits in the size.
		String small = AttachmentPromptBuilder.build(List.of(attachment("a.txt", "text/plain", 4096)), EXTRACTOR, 10);
		String huge = AttachmentPromptBuilder.build(List.of(attachment("a.txt", "text/plain", 40L * 1024 * 1024)), EXTRACTOR, 10);

		assertTrue(Math.abs(small.length() - huge.length()) < 8,
			"a bigger file must not mean a bigger prompt: " + small.length() + " vs " + huge.length());
	}

	@Test
	public void testHumanSizeDoesNotFollowTheServerLocale() {
		// A prompt is not a UI string. Under a German default locale String.format would write
		// "1,2 MB", which reads as a different number to a model shown "1.2 MB" everywhere else.
		assertEquals("1.2 MB", AttachmentPromptBuilder.humanSize(1_258_291));
		assertEquals("2.0 GB", AttachmentPromptBuilder.humanSize(2_147_483_648L));
		assertEquals("4 KB", AttachmentPromptBuilder.humanSize(4096));
		assertEquals("512 B", AttachmentPromptBuilder.humanSize(512));
	}

}
