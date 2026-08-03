package io.metaloom.cortex.node.metadata;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.writefilter.StandardWriteFilterFactory;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.helpers.DefaultHandler;

import com.adobe.internal.xmp.XMPIterator;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.XMPMetaFactory;
import com.adobe.internal.xmp.properties.XMPPropertyInfo;
import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;

import io.metaloom.cortex.api.media.ProcessableMedia;

/**
 * Layer 1: a file becomes a lossless, source-qualified {@link RawMetadata} view.
 *
 * <h2>Why two readers</h2>
 *
 * <p>
 * Images go through <b>metadata-extractor</b> directly and everything else through <b>Tika</b>.
 * That split is the whole reason this class exists rather than reusing the {@code tika} node's
 * parser: Tika's own image path ({@code ImageMetadataExtractor}) flattens EXIF, IPTC and XMP into
 * one namespace and applies its own precedence while doing so - its IPTC handler simply overwrites
 * whatever the EXIF handler put in {@code dc:title}. That is exactly the information
 * {@link MetadataMapper}'s rules are stated in terms of, so it has to survive to layer 2.
 * </p>
 *
 * <p>
 * For documents, audio and video the opposite is true: Tika normalises PDF Info, OOXML/ODF
 * properties, ID3 and MP4 {@code ilst} into one vocabulary, and reimplementing that would be
 * pointless. Those all land under the {@code container:} source, which ranks below the authored
 * standards.
 * </p>
 *
 * <h2>What it does not do</h2>
 *
 * <p>
 * No body text is extracted. The content handler is a {@link DefaultHandler} that discards
 * everything - not a {@code BodyContentHandler}, which would buffer a 400-page PDF in heap for a
 * result nobody reads. Extracting document text is the {@code tika} node's job.
 * </p>
 *
 * <p>
 * A file that carries no metadata is not an error. A parser that <em>fails</em> is: this throws, and
 * the node reports FAILED rather than quietly recording an empty envelope.
 * </p>
 */
public final class MetadataExtractor {

	private static final Logger log = LoggerFactory.getLogger(MetadataExtractor.class);

	/**
	 * Belt and braces against the size problem in §"Known hard parts": a PSD with layer metadata or a
	 * RAW with full maker notes can carry megabytes. The {@code raw} caps in the options bound what
	 * is <em>stored</em>; this bounds what is ever held in heap.
	 */
	private static final int MAX_TOTAL_METADATA_BYTES = 2 * 1024 * 1024;

	private static final int MAX_FIELD_BYTES = 64 * 1024;

	/** Namespace for the unmapped tag dump. Never read by {@link MetadataMapper}. */
	private static final String TAG_DUMP = "tag";

	/** EXIF tags worth a canonical name. Everything else survives in the raw dump. */
	private static final Map<Integer, String> EXIF_TAGS = new LinkedHashMap<>();

	/** Enumerated EXIF tags where the decoded description beats the raw ordinal. */
	private static final Map<Integer, String> EXIF_DESCRIBED_TAGS = new LinkedHashMap<>();

	private static final Map<Integer, String> IPTC_TAGS = new LinkedHashMap<>();

	static {
		EXIF_TAGS.put(ExifDirectoryBase.TAG_IMAGE_DESCRIPTION, "ImageDescription");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_MAKE, "Make");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_MODEL, "Model");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_ORIENTATION, "Orientation");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_SOFTWARE, "Software");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_ARTIST, "Artist");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_COPYRIGHT, "Copyright");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_EXPOSURE_TIME, "ExposureTime");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_FNUMBER, "FNumber");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_ISO_EQUIVALENT, "ISOSpeedRatings");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_DATETIME_ORIGINAL, "DateTimeOriginal");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL, "OffsetTimeOriginal");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_FLASH, "Flash");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_FOCAL_LENGTH, "FocalLength");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH, "FocalLengthIn35mmFilm");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_USER_COMMENT, "UserComment");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_LENS_MODEL, "LensModel");
		EXIF_TAGS.put(ExifDirectoryBase.TAG_BITS_PER_SAMPLE, "BitsPerSample");

		EXIF_DESCRIBED_TAGS.put(ExifDirectoryBase.TAG_COLOR_SPACE, "ColorSpace");
		EXIF_DESCRIBED_TAGS.put(ExifDirectoryBase.TAG_WHITE_BALANCE, "WhiteBalance");

		IPTC_TAGS.put(IptcDirectory.TAG_OBJECT_NAME, "ObjectName");
		IPTC_TAGS.put(IptcDirectory.TAG_HEADLINE, "Headline");
		IPTC_TAGS.put(IptcDirectory.TAG_CAPTION, "Caption-Abstract");
		IPTC_TAGS.put(IptcDirectory.TAG_BY_LINE, "By-line");
		IPTC_TAGS.put(IptcDirectory.TAG_CREDIT, "Credit");
		IPTC_TAGS.put(IptcDirectory.TAG_SOURCE, "Source");
		IPTC_TAGS.put(IptcDirectory.TAG_COPYRIGHT_NOTICE, "CopyrightNotice");
		IPTC_TAGS.put(IptcDirectory.TAG_CITY, "City");
		IPTC_TAGS.put(IptcDirectory.TAG_PROVINCE_OR_STATE, "Province-State");
		IPTC_TAGS.put(IptcDirectory.TAG_COUNTRY_OR_PRIMARY_LOCATION_NAME, "Country-PrimaryLocationName");
		IPTC_TAGS.put(IptcDirectory.TAG_LANGUAGE_IDENTIFIER, "LanguageIdentifier");
	}

	/**
	 * Shared, stateless, and safe to reuse: an {@code AutoDetectParser} with no custom parser list.
	 * Unlike the {@code tika} node's hand-picked list this includes the image parsers - not because
	 * this class uses them, but because leaving the default alone means a format Tika learns to read
	 * is read, rather than silently falling through to the "no parser" branch.
	 */
	private static final AutoDetectParser PARSER = new AutoDetectParser();

	private MetadataExtractor() {
	}

	/**
	 * Read everything the file says about itself.
	 *
	 * @param media   the media item
	 * @param options {@code readXmpSidecar} and {@code excludeKeys} are read here; the rest belong to
	 *                layer 2
	 * @return the raw view; never null, possibly empty
	 * @throws Exception when a parser fails. A file that simply carries nothing does not throw.
	 */
	public static RawMetadata extract(ProcessableMedia media, MetadataNodeOptions options) throws Exception {
		RawMetadata raw = new RawMetadata();

		String mimeType = detect(media);
		raw.putQuiet(RawMetadata.CONTAINER, "ContentType", mimeType);

		if (media.isImage()) {
			readImage(media, raw);
		} else {
			readContainer(media, raw);
		}

		if (options.isReadXmpSidecar()) {
			readXmpSidecar(media, raw);
		}

		raw.remove(options.getExcludeKeys());
		return raw;
	}

	/**
	 * The MIME type, from the bytes rather than the extension where possible. Becomes
	 * {@code dc.format}, and {@code dc.type} is derived from it.
	 */
	private static String detect(ProcessableMedia media) {
		Metadata metadata = new Metadata();
		metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, media.path().getFileName().toString());
		try (InputStream in = new BufferedInputStream(media.open())) {
			return PARSER.getDetector().detect(in, metadata).toString();
		} catch (Exception e) {
			log.debug("Could not detect the media type of {}: {}", media.path(), e.getMessage());
			return null;
		}
	}

	// ---- Images: metadata-extractor ----

	private static void readImage(ProcessableMedia media, RawMetadata raw) throws Exception {
		com.drew.metadata.Metadata drew;
		try (InputStream in = new BufferedInputStream(media.open())) {
			drew = ImageMetadataReader.readMetadata(in);
		}
		for (Directory directory : drew.getDirectories()) {
			if (directory instanceof GpsDirectory gps) {
				readGps(gps, raw);
			} else if (directory instanceof ExifDirectoryBase) {
				readExif(directory, raw);
			} else if (directory instanceof IptcDirectory iptc) {
				readIptc(iptc, raw);
			} else if (directory instanceof XmpDirectory xmp) {
				readXmp(xmp, raw);
			}
			dumpTags(directory, raw);
		}
	}

	private static void readExif(Directory directory, RawMetadata raw) {
		EXIF_TAGS.forEach((tag, name) -> {
			if (directory.containsTag(tag)) {
				raw.put(RawMetadata.EXIF, name, directory.getString(tag));
			}
		});
		EXIF_DESCRIBED_TAGS.forEach((tag, name) -> {
			if (directory.containsTag(tag)) {
				raw.put(RawMetadata.EXIF, name, directory.getDescription(tag));
			}
		});
	}

	/**
	 * The GPS IFD. Coordinates leave here as <b>signed decimal degrees</b> - the reader has already
	 * combined the three rationals with their N/S and E/W reference tags, which is precisely the
	 * arithmetic that gets a hand-rolled EXIF reader wrong in the southern or western hemisphere.
	 */
	private static void readGps(GpsDirectory directory, RawMetadata raw) {
		GeoLocation location = directory.getGeoLocation();
		if (location != null && !location.isZero()) {
			raw.put(RawMetadata.EXIF, "GPSLatitude", String.valueOf(location.getLatitude()));
			raw.put(RawMetadata.EXIF, "GPSLongitude", String.valueOf(location.getLongitude()));
		}
		if (directory.containsTag(GpsDirectory.TAG_ALTITUDE)) {
			Double altitude = signedAltitude(directory);
			if (altitude != null) {
				raw.put(RawMetadata.EXIF, "GPSAltitude", String.valueOf(altitude));
			}
		}
		if (directory.containsTag(GpsDirectory.TAG_IMG_DIRECTION)) {
			raw.put(RawMetadata.EXIF, "GPSImgDirection", directory.getString(GpsDirectory.TAG_IMG_DIRECTION));
		}
		if (directory.containsTag(GpsDirectory.TAG_H_POSITIONING_ERROR)) {
			raw.put(RawMetadata.EXIF, "GPSHPositioningError",
				directory.getString(GpsDirectory.TAG_H_POSITIONING_ERROR));
		}
		java.util.Date gpsDate = directory.getGpsDate();
		if (gpsDate != null) {
			// The GPS timestamp is UTC by definition - the one date in EXIF that carries a zone.
			raw.put(RawMetadata.EXIF, "GPSDateTime",
				DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(gpsDate.getTime()).atZone(ZoneOffset.UTC)));
		}
	}

	/**
	 * Altitude, signed by its reference tag: {@code GPSAltitudeRef} 1 means below sea level.
	 */
	private static Double signedAltitude(GpsDirectory directory) {
		com.drew.lang.Rational altitude = directory.getRational(GpsDirectory.TAG_ALTITUDE);
		if (altitude == null) {
			return null;
		}
		Integer ref = directory.getInteger(GpsDirectory.TAG_ALTITUDE_REF);
		double value = altitude.doubleValue();
		return ref != null && ref == 1 ? -value : value;
	}

	private static void readIptc(IptcDirectory directory, RawMetadata raw) {
		IPTC_TAGS.forEach((tag, name) -> {
			if (directory.containsTag(tag)) {
				raw.put(RawMetadata.IPTC, name, directory.getString(tag));
			}
		});
		// Keywords repeat: one IPTC dataset per keyword, so a single getString would keep only one.
		String[] keywords = directory.getStringArray(IptcDirectory.TAG_KEYWORDS);
		if (keywords != null) {
			for (String keyword : keywords) {
				raw.put(RawMetadata.IPTC, "Keywords", keyword);
			}
		}
	}

	private static void readXmp(XmpDirectory directory, RawMetadata raw) {
		raw.touch(RawMetadata.XMP);
		directory.getXmpProperties().forEach((path, value) -> putXmpProperty(raw, RawMetadata.XMP, path, value));
	}

	// ---- Documents, audio, video: Tika ----

	private static void readContainer(ProcessableMedia media, RawMetadata raw) throws Exception {
		Metadata metadata = new Metadata();
		metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, media.path().getFileName().toString());

		StandardWriteFilterFactory filterFactory = new StandardWriteFilterFactory();
		filterFactory.setMaxTotalEstimatedBytes(MAX_TOTAL_METADATA_BYTES);
		filterFactory.setMaxFieldSize(MAX_FIELD_BYTES);
		metadata.setMetadataWriteFilter(filterFactory.newInstance());

		try (InputStream in = new BufferedInputStream(media.open())) {
			// DefaultHandler, not BodyContentHandler: the body is deliberately thrown away as it is
			// produced rather than buffered and then ignored.
			PARSER.parse(in, new DefaultHandler(), metadata, new ParseContext());
		}

		single(raw, metadata, TikaCoreProperties.TITLE.getName(), "Title");
		single(raw, metadata, TikaCoreProperties.DESCRIPTION.getName(), "Subject");
		single(raw, metadata, TikaCoreProperties.CREATED.getName(), "CreateDate");
		single(raw, metadata, TikaCoreProperties.MODIFIED.getName(), "ModifyDate");
		single(raw, metadata, TikaCoreProperties.RIGHTS.getName(), "Rights");
		single(raw, metadata, TikaCoreProperties.PUBLISHER.getName(), "Publisher");
		single(raw, metadata, TikaCoreProperties.LANGUAGE.getName(), "Language");
		single(raw, metadata, TikaCoreProperties.IDENTIFIER.getName(), "Identifier");
		single(raw, metadata, "xmp:CreatorTool", "CreatorTool");
		single(raw, metadata, "pdf:producer", "Producer");
		single(raw, metadata, "xmpTPg:NPages", "PageCount");
		single(raw, metadata, "xmpDM:album", "Album");
		single(raw, metadata, "xmpDM:genre", "Genre");
		single(raw, metadata, "xmpDM:tempo", "BPM");
		single(raw, metadata, "xmpDM:trackNumber", "TrackNumber");
		single(raw, metadata, "xmpDM:releaseDate", "ReleaseDate");

		multi(raw, metadata, TikaCoreProperties.CREATOR.getName(), "Author");
		multi(raw, metadata, TikaCoreProperties.SUBJECT.getName(), "Keywords");

		// Everything Tika produced, under its own name, so nothing is lost to the whitelist above.
		// Quiet: Tika's own bookkeeping (X-TIKA:Parsed-By and friends) is present in every file and
		// says nothing about which standards the content carries.
		for (String name : metadata.names()) {
			for (String value : metadata.getValues(name)) {
				raw.putQuiet(RawMetadata.CONTAINER, name, value);
			}
		}
	}

	private static void single(RawMetadata raw, Metadata metadata, String tikaKey, String canonicalKey) {
		raw.put(RawMetadata.CONTAINER, canonicalKey, metadata.get(tikaKey));
	}

	private static void multi(RawMetadata raw, Metadata metadata, String tikaKey, String canonicalKey) {
		for (String value : metadata.getValues(tikaKey)) {
			raw.put(RawMetadata.CONTAINER, canonicalKey, value);
		}
	}

	// ---- XMP sidecar ----

	/**
	 * Merge {@code <asset>.xmp} sitting next to the media, when there is one.
	 *
	 * <p>
	 * A miss is <b>normal</b> and never a failure: an object-store-backed media item has no sibling
	 * file at all, and most files simply do not have a sidecar. Both the {@code photo.jpg.xmp} and
	 * the {@code photo.xmp} conventions are accepted, because both are in the wild.
	 * </p>
	 */
	private static void readXmpSidecar(ProcessableMedia media, RawMetadata raw) {
		Path sidecar = findSidecar(media);
		if (sidecar == null) {
			return;
		}
		try (InputStream in = Files.newInputStream(sidecar)) {
			XMPMeta meta = XMPMetaFactory.parse(in);
			raw.touch(RawMetadata.SIDECAR);
			for (XMPIterator it = meta.iterator(); it.hasNext();) {
				XMPPropertyInfo property = (XMPPropertyInfo) it.next();
				putXmpProperty(raw, RawMetadata.SIDECAR, property.getPath(), property.getValue());
			}
		} catch (Exception e) {
			// A malformed sidecar must not fail an otherwise fine ingest; the media's own metadata
			// is still worth having, and the file the user should fix is named in the log.
			log.warn("Ignoring unreadable XMP sidecar {}: {}", sidecar, e.getMessage());
		}
	}

	private static Path findSidecar(ProcessableMedia media) {
		Path path;
		try {
			path = media.path();
		} catch (RuntimeException e) {
			return null;
		}
		if (path == null || path.getParent() == null) {
			return null;
		}
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		for (String candidate : new String[] { name + ".xmp", stem + ".xmp", stem + ".XMP" }) {
			Path sidecar = path.getParent().resolve(candidate);
			if (Files.isRegularFile(sidecar)) {
				return sidecar;
			}
		}
		return null;
	}

	/**
	 * Record one XMP property under its {@code prefix:localName} path.
	 *
	 * <p>
	 * XMP paths carry array indices ({@code dc:subject[3]}) and qualifier suffixes
	 * ({@code dc:title[1]/?xml:lang}). The index is dropped - each value is recorded separately and
	 * {@link RawMetadata} keeps the order - and qualifiers are skipped: a language qualifier is not a
	 * value, and storing it would let {@code "x-default"} win a title.
	 * </p>
	 */
	private static void putXmpProperty(RawMetadata raw, String source, String path, String value) {
		if (path == null || value == null || path.indexOf('/') >= 0) {
			return;
		}
		int bracket = path.indexOf('[');
		String key = bracket > 0 ? path.substring(0, bracket) : path;
		// A schema root ("dc:") or a struct container carries no value of its own.
		if (key.isEmpty() || key.endsWith(":")) {
			return;
		}
		raw.put(source, key, value);
	}

	// ---- Raw dump ----

	/**
	 * Every tag the reader saw, under {@code tag:<directory>/<tag>} - the diagnostic dump that makes
	 * a mapping gap visible without a re-parse. Its own namespace, so it can never collide with the
	 * canonical keys the precedence rules read, and quiet, so it never claims a source contributed.
	 */
	private static void dumpTags(Directory directory, RawMetadata raw) {
		if (directory.getTags() == null) {
			return;
		}
		for (Tag tag : directory.getTags()) {
			String description;
			try {
				description = tag.getDescription();
			} catch (Exception e) {
				// A corrupt maker note yields an unformattable tag. Nothing else about the file is
				// suspect, so skip the tag rather than the file.
				continue;
			}
			raw.putQuiet(TAG_DUMP, directory.getName() + "/" + tag.getTagName(), description);
		}
	}

	/**
	 * The file's modification time as an ISO-8601 instant, or null - the {@code dateFallback}
	 * source. Read here because the mapper is deliberately kept free of file access.
	 */
	public static String modifiedIso(ProcessableMedia media) {
		try {
			return DateTimeFormatter.ISO_INSTANT.format(Files.getLastModifiedTime(media.path()).toInstant());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
