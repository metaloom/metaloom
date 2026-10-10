package io.metaloom.cortex.node.tika;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import io.metaloom.cortex.api.media.ProcessableMedia;

/**
 * Extracts a document's body text with Tika.
 *
 * <p>
 * 🔴 This used to print the metadata names to {@code System.out} and return {@code null}, which meant the {@code content} port was never written and
 * every asset was persisted with an empty {@code tika} component - a node that reported DONE for work it had thrown away. The text the parser produces
 * is the whole point, so it is what comes back.
 * </p>
 *
 * <p>
 * Metadata is deliberately not returned: reading the metadata a file carries is the {@code metadata} node's job, and it reads EXIF/IPTC/XMP directly
 * rather than through Tika's flattening image path.
 * </p>
 */
public class MediaTikaParser {

	private static final Logger log = LoggerFactory.getLogger(MediaTikaParser.class);

	/**
	 * Upper bound on the characters kept from one document.
	 *
	 * <p>
	 * A limit is unavoidable - the handler holds the text in heap and a scanned book is unbounded - but reaching it must not fail the item. Tika's
	 * default {@link BodyContentHandler} limit is 100,000 characters and it signals the limit by <em>throwing</em>, so any long document failed
	 * outright. Here the limit is raised and the exception is recognised, so a long document yields its first {@value #MAX_CONTENT_CHARS} characters
	 * instead of nothing.
	 * </p>
	 */
	public static final int MAX_CONTENT_CHARS = 2_000_000;

	/**
	 * Exclusions
	 */
	private static final Set<MediaType> EXCLUDES = new HashSet<>(Arrays.asList(
		MediaType.application("vnd.ms-visio.drawing"),
		MediaType.application("vnd.ms-visio.drawing.macroenabled.12"),
		MediaType.application("vnd.ms-visio.stencil"),
		MediaType.application("vnd.ms-visio.stencil.macroenabled.12"),
		MediaType.application("vnd.ms-visio.template"),
		MediaType.application("vnd.ms-visio.template.macroenabled.12")));

	/**
	 * Supported parsers.
	 */
	private static final Parser[] PARSERS = new Parser[] {
		// documents
		new org.apache.tika.parser.html.JSoupParser(),
		// new org.apache.tika.parser.rtf.RTFParser(),
		new org.apache.tika.parser.pdf.PDFParser(),
		new org.apache.tika.parser.txt.TXTParser(),
		new org.apache.tika.parser.microsoft.OfficeParser(),
		new org.apache.tika.parser.microsoft.OldExcelParser(),
		ParserDecorator.withoutTypes(xmlParser(), EXCLUDES),
		new org.apache.tika.parser.odf.OpenDocumentParser(),
		new org.apache.tika.parser.iwork.IWorkPackageParser(),
		new org.apache.tika.parser.xml.DcXMLParser(),
		new org.apache.tika.parser.epub.EpubParser(),

		// audio
		new org.apache.tika.parser.audio.AudioParser(),
		new org.apache.tika.parser.mp3.Mp3Parser(),

		// video
		new org.apache.tika.parser.video.FLVParser(),
		new org.apache.tika.parser.mp4.MP4Parser(),

		// images
		new org.apache.tika.parser.image.ImageParser(),
		// new org.apache.tika.parser.jpeg.JpegParser(),
		new org.apache.tika.parser.image.WebPParser(),

		// ogg (audio/video)
		new org.gagravarr.tika.OggParser()
	};

	private static OOXMLParser xmlParser() {
		OOXMLParser ooxmlParser = new OOXMLParser();
		ooxmlParser.setUseSAXDocxExtractor(true);
		ooxmlParser.setUseSAXPptxExtractor(true);
		return ooxmlParser;
	}

	private static final AutoDetectParser PARSER_INSTANCE = new AutoDetectParser(PARSERS);

	private static final AutoDetectParser TIKA_INSTANCE = new AutoDetectParser(PARSER_INSTANCE.getDetector(), PARSER_INSTANCE);

	private MediaTikaParser() {
	}

	/**
	 * Parse {@code media} and return its body text.
	 *
	 * @param media
	 *            the item to parse
	 * @return the extracted text, or null when the file carries none. Null rather than {@code ""} so the caller can leave the {@code content} port
	 *         unwritten instead of announcing an empty document
	 * @throws IOException
	 *             when the bytes cannot be read
	 * @throws TikaException
	 *             when no parser could handle them
	 * @throws SAXException
	 *             when the content handler fails for a reason other than the write limit
	 */
	public static String parse(ProcessableMedia media) throws IOException, TikaException, SAXException {
		StringWriter text = new StringWriter();
		BodyContentHandler handler = new BodyContentHandler(new WriteOutContentHandler(text, MAX_CONTENT_CHARS));

		Metadata metadata = new Metadata();
		metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName(media));

		try (InputStream ins = media.open()) {
			TIKA_INSTANCE.parse(ins, handler, metadata, new ParseContext());
		} catch (SAXException e) {
			// Hitting the limit is a truncation, not a failure: what was read is still worth keeping.
			if (!WriteLimitReachedException.isWriteLimitReached(e)) {
				throw e;
			}
			log.info("Truncated the text of {} at {} characters", media.path(), MAX_CONTENT_CHARS);
		}

		String content = text.toString().trim();
		return content.isEmpty() ? null : content;
	}

	/**
	 * The file name, used only as a detection hint. A media item that cannot name itself is still parseable from its bytes alone.
	 */
	private static String fileName(ProcessableMedia media) {
		try {
			return media.path().getFileName().toString();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
