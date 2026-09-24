package io.metaloom.loom.mcp.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Reads the text family — everything that is already characters on disk.
 *
 * <p>
 * No dependencies, deliberately. This covers the formats a user actually drops into a chat to be
 * read: notes, briefs, transcripts, CSV exports, JSON payloads, configuration and source code.
 * </p>
 *
 * <p>
 * ⚠️ {@code isReadableKind} in {@code loom-ui/src/api/chatAttachments.ts} mirrors {@link #supports}
 * so the attachment chip can say whether a file is readable the moment it is dropped, without a
 * round trip. This class is the authority; when the list here grows, that one has to grow with it or
 * a chip will promise something {@code read_attachment} refuses.
 * </p>
 */
@Singleton
public class PlainTextExtractor implements AttachmentTextExtractor {

	/**
	 * Types outside {@code text/*} that are nevertheless text.
	 *
	 * <p>
	 * Browsers label these {@code application/…} even though the bytes are characters, and a user
	 * dropping a JSON export has no way to know or care.
	 * </p>
	 */
	private static final Set<String> TEXTUAL_APPLICATION_TYPES = Set.of(
		"application/json",
		"application/xml",
		"application/xhtml+xml",
		"application/javascript",
		"application/ecmascript",
		"application/x-yaml",
		"application/yaml",
		"application/x-sh",
		"application/sql",
		"application/toml",
		"application/csv",
		"application/x-ndjson");

	@Inject
	public PlainTextExtractor() {
	}

	@Override
	public boolean supports(String mimeType) {
		String type = normalize(mimeType);
		if (type == null) {
			return false;
		}
		if (type.startsWith("text/")) {
			return true;
		}
		// Structured suffixes: application/vnd.api+json, image/svg+xml, application/ld+json, ...
		// RFC 6839 says the suffix determines the underlying syntax, so +json and +xml are text
		// whatever the vendor tree in front of them says.
		if (type.endsWith("+json") || type.endsWith("+xml")) {
			return true;
		}
		return TEXTUAL_APPLICATION_TYPES.contains(type);
	}

	@Override
	public String extract(InputStream in, String mimeType) throws IOException {
		byte[] bytes = in.readAllBytes();
		return decode(bytes);
	}

	/**
	 * Decode as UTF-8, and if that fails, as ISO-8859-1.
	 *
	 * <p>
	 * The fallback is not a guess at the real encoding — it is the one decoding that cannot fail,
	 * because every byte is a valid ISO-8859-1 character. A Windows-1252 CSV therefore arrives with
	 * a few mangled quotation marks rather than with a replacement character every other word, and
	 * the agent can still read it. Guessing properly would need charset detection, which is a
	 * dependency for a rare case.
	 * </p>
	 */
	static String decode(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(java.nio.ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, StandardCharsets.ISO_8859_1);
		}
	}

	/** Strip the parameters a Content-Type carries ({@code text/csv; charset=utf-8}) and lowercase. */
	static String normalize(String mimeType) {
		if (mimeType == null || mimeType.isBlank()) {
			return null;
		}
		int semicolon = mimeType.indexOf(';');
		String type = semicolon < 0 ? mimeType : mimeType.substring(0, semicolon);
		return type.trim().toLowerCase();
	}

}
