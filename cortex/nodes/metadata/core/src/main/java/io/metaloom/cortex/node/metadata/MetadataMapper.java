package io.metaloom.cortex.node.metadata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 2: the raw, source-qualified key/value view becomes one canonical envelope.
 *
 * <h2>Precedence</h2>
 *
 * <p>
 * A photo out of a modern editor routinely carries its caption three times, in three standards, with
 * two different values. Which one wins is a <b>design decision</b>, written down here rather than
 * left to whichever parser happened to run last. The rules follow the Metadata Working Group
 * guidelines:
 * </p>
 *
 * <table border="1">
 * <caption>First non-empty wins</caption>
 * <tr><th>Field</th><th>Order</th></tr>
 * <tr><td>{@code dc.title}</td><td>XMP {@code dc:title} → IPTC {@code ObjectName} → EXIF {@code ImageDescription} → container {@code Title}</td></tr>
 * <tr><td>{@code dc.description}</td><td>XMP {@code dc:description} → IPTC {@code Caption-Abstract} → EXIF {@code UserComment} → container {@code Subject}</td></tr>
 * <tr><td>{@code dc.creator}</td><td>XMP {@code dc:creator} → IPTC {@code By-line} → EXIF {@code Artist} → container {@code Author}</td></tr>
 * <tr><td>{@code dc.subject}</td><td>XMP {@code dc:subject} → IPTC {@code Keywords} → container {@code Keywords}</td></tr>
 * <tr><td>{@code dc.date}</td><td>EXIF {@code DateTimeOriginal} → XMP {@code photoshop:DateCreated} → XMP {@code xmp:CreateDate} → container date → (opt-in) file mtime</td></tr>
 * <tr><td>{@code dc.rights}</td><td>XMP {@code dc:rights} → IPTC {@code CopyrightNotice} → EXIF {@code Copyright}</td></tr>
 * <tr><td>{@code rights.licenseUrl}</td><td>XMP {@code cc:license} → XMP {@code xmpRights:WebStatement} → a URL inside the rights statement</td></tr>
 * <tr><td>{@code geo}</td><td>EXIF GPS IFD → XMP {@code exif:GPS*} → sidecar. <b>Never</b> IPTC {@code City}, which is a name</td></tr>
 * <tr><td>{@code capture}</td><td>EXIF → XMP {@code exif:} mirror</td></tr>
 * <tr><td>{@code dc.language}</td><td>XMP {@code dc:language} → container language → null</td></tr>
 * </table>
 *
 * <p>
 * <b>EXIF beats XMP for dates and camera data; XMP beats EXIF for authored text.</b> The asymmetry
 * is deliberate and is the single most important line in this class: EXIF dates are written by the
 * camera while XMP dates are rewritten by every editor that touches the file, whereas EXIF's text
 * fields are ASCII-limited and mangle every non-Latin script.
 * </p>
 *
 * <p>
 * A {@code sidecar} value ranks immediately below the equivalent embedded XMP one - it is the same
 * standard in a different place, and in a Lightroom-style workflow it is frequently the only place
 * the edits exist.
 * </p>
 *
 * <p>
 * <b>MetaLoom's own values never outrank a human's.</b> A field carrying the {@code metaloom:}
 * provenance marker - written back into the file by a future write-back node - is ingested at the
 * <em>lowest</em> rank. Without that rule the write → re-ingest → re-write loop promotes a machine
 * guess to authored ground truth and the catalogue degrades a little on every pass.
 * </p>
 *
 * <p>
 * Whichever key wins is recorded in {@link AssetMetadata#getProvenance()}, so a mapping bug is
 * fixable by re-normalising rather than by re-reading every file in the library.
 * </p>
 */
public final class MetadataMapper {

	/** {@code 2019:04:03 05:12:44} - the EXIF date format, which is not ISO-8601. */
	private static final Pattern EXIF_DATE = Pattern
		.compile("^(\\d{4}):(\\d{2}):(\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(.*)$");

	/** A leading number, with an optional denominator and an optional unit suffix. */
	private static final Pattern RATIONAL = Pattern.compile("^\\s*(-?[\\d.]+)\\s*/\\s*([\\d.]+)");

	private static final Pattern LEADING_NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

	private MetadataMapper() {
	}

	/**
	 * Normalise the raw view into the canonical envelope.
	 *
	 * @param raw            layer 1, source-qualified
	 * @param options        the node options; {@code gpsPolicy}, {@code includeRaw} and
	 *                       {@code dateFallback} all change the output, which is why they also
	 *                       participate in the node's cache key
	 * @param fileModifiedIso the file's modification time as an ISO-8601 string, or null. Used only
	 *                       when {@code dateFallback = FILESYSTEM}: a filesystem timestamp says when
	 *                       a copy was written, not when a photo was taken, so it is a last resort
	 *                       and never a default
	 * @return the envelope; never null, possibly empty
	 */
	public static AssetMetadata map(RawMetadata raw, MetadataNodeOptions options, String fileModifiedIso) {
		AssetMetadata out = new AssetMetadata();
		out.getSources().addAll(raw.sources());

		mapDublinCore(raw, out, options, fileModifiedIso);
		mapRights(raw, out, options);
		mapCapture(raw, out);
		mapGeo(raw, out, options);

		if (options.isIncludeRaw()) {
			copyRaw(raw, out, options);
		}
		return out;
	}

	// ---- Dublin Core ----

	private static void mapDublinCore(RawMetadata raw, AssetMetadata out, MetadataNodeOptions options,
		String fileModifiedIso) {
		DcBlock dc = out.getDc();

		dc.setTitle(pick(raw, out, "dc.title",
			xmp("dc:title"), iptc("ObjectName"), iptc("Title"), exif("ImageDescription"), container("Title")));

		dc.setDescription(pick(raw, out, "dc.description",
			xmp("dc:description"), iptc("Caption-Abstract"), exif("UserComment"), container("Subject")));

		dc.getCreator().addAll(pickAll(raw, out, "dc.creator",
			xmp("dc:creator"), iptc("By-line"), exif("Artist"), container("Author")));

		dc.getSubject().addAll(splitKeywords(pickAll(raw, out, "dc.subject",
			xmp("dc:subject"), iptc("Keywords"), container("Keywords"))));

		dc.setPublisher(pick(raw, out, "dc.publisher", xmp("dc:publisher"), container("Publisher")));

		dc.getContributor().addAll(pickAll(raw, out, "dc.contributor", xmp("dc:contributor")));
		dc.getRelation().addAll(pickAll(raw, out, "dc.relation", xmp("dc:relation")));

		// EXIF wins here: the camera wrote it once; every editor since has rewritten the XMP copy.
		String date = pick(raw, out, "dc.date",
			exif("DateTimeOriginal"), xmp("photoshop:DateCreated"), xmp("xmp:CreateDate"), container("CreateDate"));
		date = normalizeDate(date, raw.first(exif("OffsetTimeOriginal")));
		if (date == null && options.getDateFallback() == DateFallback.FILESYSTEM && fileModifiedIso != null) {
			date = fileModifiedIso;
			out.recordProvenance("dc.date", "file:modified");
		}
		dc.setDate(date);

		String format = pick(raw, out, "dc.format", container("ContentType"), xmp("dc:format"));
		dc.setFormat(format);
		// dc:type is a term from the DCMI Type vocabulary, not a MIME type. Getting these two
		// backwards is the classic Dublin Core mistake, so the derivation lives in one place.
		dc.setType(dcmiType(format));

		dc.setIdentifier(pick(raw, out, "dc.identifier", xmp("dc:identifier"), xmp("xmpMM:DocumentID")));
		dc.setSource(pick(raw, out, "dc.source", xmp("dc:source"), iptc("Source")));

		// Never a guess: language *detection* is a different job, done by a different node.
		dc.setLanguage(pick(raw, out, "dc.language", xmp("dc:language"), container("Language")));

		out.setCity(pick(raw, out, "geo.place.city", iptc("City"), xmp("photoshop:City")));
		out.setState(pick(raw, out, "geo.place.state", iptc("Province-State"), xmp("photoshop:State")));
		out.setCountry(pick(raw, out, "geo.place.country",
			iptc("Country-PrimaryLocationName"), iptc("Country/Primary Location Name"), xmp("photoshop:Country")));
		dc.setCoverage(out.placeLabel());
	}

	// ---- Rights ----

	private static void mapRights(RawMetadata raw, AssetMetadata out, MetadataNodeOptions options) {
		RightsBlock rights = out.getRights();
		DcBlock dc = out.getDc();

		String statement = pick(raw, out, "dc.rights",
			xmp("dc:rights"), iptc("CopyrightNotice"), exif("Copyright"));
		dc.setRights(statement);
		rights.setStatement(statement);

		rights.setHolder(pick(raw, out, "rights.holder",
			xmp("dcterms:rightsHolder"), xmp("cc:attributionName"), iptc("By-line")));
		rights.setCredit(pick(raw, out, "rights.credit", iptc("Credit"), xmp("photoshop:Credit")));
		rights.setUsageTerms(pick(raw, out, "rights.usageTerms", xmp("xmpRights:UsageTerms")));
		rights.setWebStatement(pick(raw, out, "rights.webStatement", xmp("xmpRights:WebStatement")));

		String licenseUrl = pick(raw, out, "rights.licenseUrl",
			xmp("cc:license"), xmp("xmpRights:WebStatement"));
		if (licenseUrl == null && statement != null) {
			licenseUrl = LicenseResolver.findUrl(statement);
			if (licenseUrl != null) {
				out.recordProvenance("rights.licenseUrl", "derived:dc.rights");
			}
		}
		rights.setLicenseUrl(licenseUrl);
		if (options.isLicenseDetection()) {
			rights.setLicenseId(LicenseResolver.resolve(licenseUrl));
		}

		String marked = pick(raw, out, "rights.marked", xmp("xmpRights:Marked"));
		if (marked != null) {
			rights.setMarked(Boolean.parseBoolean(marked));
		} else if (statement != null) {
			// A copyright notice is an assertion of rights even when nothing says so explicitly.
			rights.setMarked(Boolean.TRUE);
			out.recordProvenance("rights.marked", "derived:dc.rights");
		}
	}

	// ---- Capture ----

	private static void mapCapture(RawMetadata raw, AssetMetadata out) {
		CaptureBlock capture = out.getCapture();

		capture.setMake(pick(raw, out, "capture.make", exif("Make"), xmp("tiff:Make")));
		capture.setModel(pick(raw, out, "capture.model", exif("Model"), xmp("tiff:Model")));
		capture.setLens(pick(raw, out, "capture.lens", exif("LensModel"), xmp("aux:Lens")));
		capture.setSoftware(pick(raw, out, "capture.software",
			exif("Software"), xmp("xmp:CreatorTool"), container("CreatorTool")));

		capture.setDateTimeOriginal(normalizeDate(
			pick(raw, out, "capture.dateTimeOriginal", exif("DateTimeOriginal")),
			raw.first(exif("OffsetTimeOriginal"))));

		capture.setExposureTime(toSeconds(pick(raw, out, "capture.exposureTime",
			exif("ExposureTime"), xmp("exif:ExposureTime"))));
		capture.setFNumber(toDouble(pick(raw, out, "capture.fNumber", exif("FNumber"), xmp("exif:FNumber"))));
		capture.setIso(toInteger(pick(raw, out, "capture.iso",
			exif("ISOSpeedRatings"), exif("PhotographicSensitivity"), xmp("exif:ISOSpeedRatings"))));
		capture.setFocalLength(toDouble(pick(raw, out, "capture.focalLength",
			exif("FocalLength"), xmp("exif:FocalLength"))));
		capture.setFocalLength35(toDouble(pick(raw, out, "capture.focalLength35",
			exif("FocalLengthIn35mmFilm"), xmp("exif:FocalLengthIn35mmFilm"))));
		capture.setFlash(toFlash(pick(raw, out, "capture.flash", exif("Flash"), xmp("exif:Flash"))));
		capture.setOrientation(toInteger(pick(raw, out, "capture.orientation",
			exif("Orientation"), xmp("tiff:Orientation"))));
		capture.setColorSpace(pick(raw, out, "capture.colorSpace", exif("ColorSpace"), xmp("exif:ColorSpace")));
		capture.setWhiteBalance(pick(raw, out, "capture.whiteBalance",
			exif("WhiteBalance"), xmp("exif:WhiteBalance")));
	}

	// ---- Geo ----

	private static void mapGeo(RawMetadata raw, AssetMetadata out, MetadataNodeOptions options) {
		if (options.getGpsPolicy() == GpsPolicy.DROP) {
			return;
		}
		// IPTC City is deliberately absent from this list. It is a place name, and a name is not a
		// coordinate - converting one into the other is geocoding.
		String lat = pick(raw, out, "geo.lat", exif("GPSLatitude"), xmp("exif:GPSLatitude"));
		String lon = pick(raw, out, "geo.lon", exif("GPSLongitude"), xmp("exif:GPSLongitude"));
		Double latitude = toDouble(lat);
		Double longitude = toDouble(lon);
		if (latitude == null || longitude == null) {
			return;
		}

		// The method is the source the winning coordinate came from - exactly what the geo
		// component's identity is keyed on - so it is read back off the provenance the pick
		// recorded rather than re-derived and risking disagreement with it.
		String method = sourceOf(out.getProvenance().get("geo.lat"));

		GeoBlock geo = new GeoBlock(round(latitude, options), round(longitude, options), method);
		geo.setAltitudeM(toDouble(raw.firstOf(expand(exif("GPSAltitude"), xmp("exif:GPSAltitude")))));
		geo.setDirectionDeg(toDouble(raw.firstOf(expand(exif("GPSImgDirection"), xmp("exif:GPSImgDirection")))));
		geo.setTimestamp(raw.firstOf(expand(exif("GPSDateTime"), xmp("exif:GPSTimeStamp"))));
		Double accuracy = toDouble(raw.first(exif("GPSHPositioningError")));
		if (accuracy != null) {
			geo.setAccuracyM(accuracy.floatValue());
		}
		out.getGeo().add(geo);

		// Decimation, not truncation: a two-hour flight truncated to its first N samples is a map
		// pin on the runway. Today no extractor emits more than one sample, so this is a no-op - the
		// cap belongs with the model it protects, not with the extractor that will one day fill it.
		decimate(out.getGeo(), options.getGpsTrackMaxSamples());
	}

	/**
	 * Reduce a track to at most {@code max} evenly spaced samples, keeping the first and last.
	 */
	static void decimate(List<GeoBlock> samples, int max) {
		if (max <= 0 || samples.size() <= max) {
			return;
		}
		List<GeoBlock> kept = new ArrayList<>(max);
		double step = (samples.size() - 1) / (double) (max - 1);
		for (int i = 0; i < max; i++) {
			kept.add(samples.get((int) Math.round(i * step)));
		}
		samples.clear();
		samples.addAll(kept);
	}

	private static double round(double value, MetadataNodeOptions options) {
		if (options.getGpsPolicy() != GpsPolicy.ROUND) {
			return value;
		}
		return BigDecimal.valueOf(value)
			.setScale(options.getGpsRoundDecimals(), RoundingMode.HALF_UP)
			.doubleValue();
	}

	// ---- Raw ----

	private static void copyRaw(RawMetadata raw, AssetMetadata out, MetadataNodeOptions options) {
		int maxKeys = options.getRawMaxKeys();
		int maxBytes = options.getRawMaxValueBytes();
		int count = 0;
		for (var entry : raw.flat().entrySet()) {
			if (count++ >= maxKeys) {
				out.getRaw().put("...", "truncated at " + maxKeys + " keys of " + raw.size());
				break;
			}
			String value = entry.getValue();
			if (value.length() > maxBytes) {
				value = value.substring(0, maxBytes) + "…";
			}
			out.getRaw().put(entry.getKey(), value);
		}
	}

	// ---- Key helpers ----

	/**
	 * An XMP lookup is always two keys: the embedded packet, then the sidecar. Same standard, same
	 * rank, different place.
	 */
	private static String[] xmpKeys(String property) {
		return new String[] { RawMetadata.XMP + ":" + property, RawMetadata.SIDECAR + ":" + property };
	}

	private static String xmp(String property) {
		return RawMetadata.XMP + ":" + property;
	}

	private static String exif(String tag) {
		return RawMetadata.EXIF + ":" + tag;
	}

	private static String iptc(String tag) {
		return RawMetadata.IPTC + ":" + tag;
	}

	private static String container(String key) {
		return RawMetadata.CONTAINER + ":" + key;
	}

	/**
	 * The source prefix of a fully qualified raw key - {@code exif:GPSLatitude} → {@code exif}.
	 */
	private static String sourceOf(String qualifiedKey) {
		if (qualifiedKey == null) {
			return RawMetadata.EXIF;
		}
		int colon = qualifiedKey.indexOf(':');
		return colon < 0 ? qualifiedKey : qualifiedKey.substring(0, colon);
	}

	/**
	 * Expand each key into the ranks it actually stands for: an {@code xmp:} key also matches the
	 * sidecar, and every key is followed at the very end by its {@code metaloom:} counterpart, which
	 * therefore only ever wins when nothing authored exists.
	 */
	private static String[] expand(String... keys) {
		Set<String> expanded = new LinkedHashSet<>();
		for (String key : keys) {
			if (key.startsWith(RawMetadata.XMP + ":")) {
				expanded.addAll(List.of(xmpKeys(key.substring(RawMetadata.XMP.length() + 1))));
			} else {
				expanded.add(key);
			}
		}
		for (String key : keys) {
			expanded.add(RawMetadata.METALOOM + ":" + key.substring(key.indexOf(':') + 1));
		}
		return expanded.toArray(String[]::new);
	}

	private static String pick(RawMetadata raw, AssetMetadata out, String field, String... keys) {
		for (String key : expand(keys)) {
			String value = raw.first(key);
			if (value != null) {
				out.recordProvenance(field, key);
				return value;
			}
		}
		return null;
	}

	private static List<String> pickAll(RawMetadata raw, AssetMetadata out, String field, String... keys) {
		for (String key : expand(keys)) {
			List<String> values = raw.all(key);
			if (!values.isEmpty()) {
				out.recordProvenance(field, key);
				return values;
			}
		}
		return List.of();
	}

	// ---- Coercion ----

	/**
	 * Split the delimited keyword lists that IPTC and Office properties use, while leaving an XMP
	 * bag - already one entry per keyword - alone.
	 */
	static List<String> splitKeywords(List<String> values) {
		List<String> keywords = new ArrayList<>();
		for (String value : values) {
			for (String part : value.split("[;,]")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty() && !keywords.contains(trimmed)) {
					keywords.add(trimmed);
				}
			}
		}
		return keywords;
	}

	/**
	 * Normalise a date to ISO-8601.
	 *
	 * <p>
	 * EXIF writes {@code 2019:04:03 05:12:44} and states no timezone. When the file also carries
	 * {@code OffsetTimeOriginal} (EXIF 2.31 and later) the offset is appended; otherwise the result is
	 * a <b>local</b> time with no offset. Assuming UTC instead would shift an evening photo into the
	 * next day and quietly corrupt every date-range query over the catalogue - so this never does.
	 * </p>
	 */
	static String normalizeDate(String value, String offset) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		Matcher matcher = EXIF_DATE.matcher(trimmed);
		if (matcher.matches()) {
			String iso = matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3) + "T" + matcher.group(4);
			String tail = matcher.group(5).trim();
			if (!tail.isEmpty()) {
				return iso + tail;
			}
			return offset == null || offset.isBlank() ? iso : iso + offset.trim();
		}
		return trimmed;
	}

	/**
	 * Exposure as seconds. Cameras write {@code 1/250}; a number is what a range query needs.
	 */
	static Double toSeconds(String value) {
		if (value == null) {
			return null;
		}
		Matcher rational = RATIONAL.matcher(value);
		if (rational.find()) {
			double denominator = Double.parseDouble(rational.group(2));
			if (denominator == 0) {
				return null;
			}
			return Double.parseDouble(rational.group(1)) / denominator;
		}
		return toDouble(value);
	}

	/**
	 * The leading number in a value that may carry a unit ({@code "35 mm"}, {@code "f/8.0"}).
	 */
	static Double toDouble(String value) {
		if (value == null) {
			return null;
		}
		Matcher matcher = LEADING_NUMBER.matcher(value);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Double.valueOf(matcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static Integer toInteger(String value) {
		Double number = toDouble(value);
		return number == null ? null : Integer.valueOf(number.intValue());
	}

	/**
	 * Did the flash fire?
	 *
	 * <p>
	 * The EXIF tag is a bit field whose bit 0 is "fired", but readers hand it over as a description
	 * string just as often as a number, and the descriptions are not uniform ("Flash did not fire",
	 * "No flash"). Both shapes are accepted; anything else yields null rather than a guess.
	 * </p>
	 */
	static Boolean toFlash(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.matches("-?\\d+")) {
			return (Integer.parseInt(normalized) & 1) != 0;
		}
		if (normalized.equals("true") || normalized.equals("false")) {
			return Boolean.valueOf(normalized);
		}
		if (normalized.contains("did not fire") || normalized.contains("no flash") || normalized.contains("not fired")) {
			return Boolean.FALSE;
		}
		if (normalized.contains("fired") || normalized.contains("flash fire")) {
			return Boolean.TRUE;
		}
		return null;
	}

	/**
	 * The DCMI Type vocabulary term for a MIME type. {@code dc:type} is a term, {@code dc:format} is
	 * the MIME type - the two are routinely swapped.
	 */
	static String dcmiType(String mimeType) {
		if (mimeType == null) {
			return null;
		}
		String type = mimeType.toLowerCase(Locale.ROOT);
		if (type.startsWith("image/")) {
			return "StillImage";
		}
		if (type.startsWith("video/")) {
			return "MovingImage";
		}
		if (type.startsWith("audio/")) {
			return "Sound";
		}
		if (type.startsWith("text/") || type.startsWith("application/")) {
			return "Text";
		}
		return null;
	}
}
