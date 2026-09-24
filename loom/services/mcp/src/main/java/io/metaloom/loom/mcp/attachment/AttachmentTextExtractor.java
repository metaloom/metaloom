package io.metaloom.loom.mcp.attachment;

import java.io.IOException;
import java.io.InputStream;

/**
 * Turns the bytes of a chat attachment into text the agent can read.
 *
 * <p>
 * This exists as an interface for one reason: the set of types Loom can read is expected to grow,
 * and nothing above it should have to change when it does. {@link PlainTextExtractor} handles the
 * text family with no dependencies at all; PDF, Word, Excel and ODF would come from a second
 * implementation backed by Tika.
 * </p>
 *
 * <p>
 * <b>Why Tika is not here yet.</b> The {@code loom-service-tika} module exists as an empty skeleton
 * and the BOM already pins Tika, so the code is small. The cost is not the code: Tika discovers its
 * parsers through {@code ServiceLoader} and reflection, and the Loom server has a GraalVM
 * native-image build that would need reflection metadata for every parser reachable from
 * {@code AutoDetectParser}. That is a separate piece of work with its own failure mode, and pinning
 * it behind this interface means it can be done — or abandoned — without touching chat attachments.
 * </p>
 *
 * <p>
 * An implementation must never throw for content it declared it supports. A truncated upload, a
 * mislabelled MIME type or bytes that are not valid UTF-8 are all ordinary and must come back as
 * whatever text could be recovered; the agent deals in sentences, and an exception here becomes a
 * failed turn.
 * </p>
 */
public interface AttachmentTextExtractor {

	/**
	 * Whether this extractor can produce text for the given MIME type.
	 *
	 * @param mimeType the stored type, which may be null or malformed — a browser supplies it
	 * @return true when {@link #extract} will return something meaningful
	 */
	boolean supports(String mimeType);

	/**
	 * Read the stream as text.
	 *
	 * <p>
	 * The caller owns the stream and closes it. Only called when {@link #supports} returned true.
	 * </p>
	 *
	 * @param in the attachment's bytes
	 * @param mimeType the stored type, passed through so one implementation can serve several types
	 * @return the extracted text, never null
	 * @throws IOException only when the bytes could not be read at all — never for content this
	 *             extractor merely found difficult
	 */
	String extract(InputStream in, String mimeType) throws IOException;

}
