package io.metaloom.cortex.node.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The precedence table, one case per row.
 *
 * <p>
 * This is where the design of the whole node actually lives, so it is tested with hand-built raw
 * metadata and <b>no fixtures at all</b>: what a particular JPEG happens to contain is a question
 * about that JPEG, whereas "XMP beats EXIF for a caption" is a decision, and a decision deserves a
 * test that states it in one line.
 * </p>
 */
class MetadataMapperTest {

	private final MetadataNodeOptions options = new MetadataNodeOptions();

	private AssetMetadata map(RawMetadata raw) {
		return MetadataMapper.map(raw, options, null);
	}

	// ---- dc.title ----

	@Test
	void testTitlePrefersXmpOverIptcOverExif() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "ImageDescription", "from exif")
			.put(RawMetadata.IPTC, "ObjectName", "from iptc")
			.put(RawMetadata.XMP, "dc:title", "from xmp");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getDc().getTitle()).isEqualTo("from xmp");
		assertThat(metadata.getProvenance()).containsEntry("dc.title", "xmp:dc:title");
	}

	@Test
	void testTitleFallsBackThroughIptcToExifToContainer() {
		assertThat(map(new RawMetadata()
			.put(RawMetadata.EXIF, "ImageDescription", "from exif")
			.put(RawMetadata.IPTC, "ObjectName", "from iptc")).getDc().getTitle()).isEqualTo("from iptc");

		assertThat(map(new RawMetadata()
			.put(RawMetadata.EXIF, "ImageDescription", "from exif")
			.put(RawMetadata.CONTAINER, "Title", "from container")).getDc().getTitle()).isEqualTo("from exif");

		assertThat(map(new RawMetadata()
			.put(RawMetadata.CONTAINER, "Title", "from container")).getDc().getTitle()).isEqualTo("from container");
	}

	// ---- dc.description ----

	@Test
	void testDescriptionPrefersXmpThenIptcThenExif() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "UserComment", "from exif")
			.put(RawMetadata.IPTC, "Caption-Abstract", "from iptc")
			.put(RawMetadata.XMP, "dc:description", "from xmp");

		assertThat(map(raw).getDc().getDescription()).isEqualTo("from xmp");

		assertThat(map(new RawMetadata()
			.put(RawMetadata.EXIF, "UserComment", "from exif")
			.put(RawMetadata.IPTC, "Caption-Abstract", "from iptc"))
				.getDc().getDescription()).isEqualTo("from iptc");
	}

	// ---- dc.creator ----

	@Test
	void testCreatorPrefersXmpThenIptcThenExifAndIsAlwaysAList() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "Artist", "Exif Artist")
			.put(RawMetadata.IPTC, "By-line", "Iptc Byline")
			.put(RawMetadata.XMP, "dc:creator", "Jane Doe")
			.put(RawMetadata.XMP, "dc:creator", "John Roe");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getDc().getCreator()).containsExactly("Jane Doe", "John Roe");
		assertThat(metadata.toJson().getJsonObject("dc").getJsonArray("creator")).hasSize(2);
	}

	@Test
	void testCreatorArrayIsPresentAndEmptyWhenNothingWasAuthored() {
		// The four list-valued Dublin Core elements are arrays even when empty. A field that is
		// sometimes a string, sometimes a list and sometimes absent is what breaks consumers.
		assertThat(map(new RawMetadata()).toJson().getJsonObject("dc").getJsonArray("creator")).isEmpty();
	}

	// ---- dc.subject ----

	@Test
	void testSubjectPrefersTheXmpBagOverIptcKeywords() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.IPTC, "Keywords", "news")
			.put(RawMetadata.XMP, "dc:subject", "sunrise")
			.put(RawMetadata.XMP, "dc:subject", "mountain");

		assertThat(map(raw).getDc().getSubject()).containsExactly("sunrise", "mountain");
	}

	@Test
	void testContainerKeywordsAreSplitOnSeparators() {
		// Office and PDF store keywords as one delimited string; XMP stores a real bag.
		RawMetadata raw = new RawMetadata().put(RawMetadata.CONTAINER, "Keywords", "japan; travel, sunrise");

		assertThat(map(raw).getDc().getSubject()).containsExactly("japan", "travel", "sunrise");
	}

	// ---- dc.date ----

	@Test
	void testDatePrefersExifOverXmp() {
		// The one place EXIF outranks XMP for good reason: the camera wrote the EXIF date once,
		// while every editor that touched the file has rewritten the XMP copy.
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "DateTimeOriginal", "2019:04:03 05:12:44")
			.put(RawMetadata.XMP, "photoshop:DateCreated", "2021-01-01T00:00:00")
			.put(RawMetadata.XMP, "xmp:CreateDate", "2022-02-02T00:00:00");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getDc().getDate()).isEqualTo("2019-04-03T05:12:44");
		assertThat(metadata.getProvenance()).containsEntry("dc.date", "exif:DateTimeOriginal");
	}

	@Test
	void testDateFallsBackFromPhotoshopToXmpToContainer() {
		assertThat(map(new RawMetadata()
			.put(RawMetadata.XMP, "photoshop:DateCreated", "2021-01-01T00:00:00")
			.put(RawMetadata.XMP, "xmp:CreateDate", "2022-02-02T00:00:00"))
				.getDc().getDate()).isEqualTo("2021-01-01T00:00:00");

		assertThat(map(new RawMetadata()
			.put(RawMetadata.CONTAINER, "CreateDate", "2023-03-03T00:00:00"))
				.getDc().getDate()).isEqualTo("2023-03-03T00:00:00");
	}

	@Test
	void testExifDateKeepsLocalTimeWhenNoOffsetIsStated() {
		// Assuming UTC would move an evening photo into the next day and silently corrupt every
		// date-range query over the catalogue.
		RawMetadata raw = new RawMetadata().put(RawMetadata.EXIF, "DateTimeOriginal", "2019:04:03 22:12:44");

		assertThat(map(raw).getDc().getDate()).isEqualTo("2019-04-03T22:12:44").doesNotContain("Z");
	}

	@Test
	void testExifDateGainsTheOffsetWhenTheFileStatesOne() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "DateTimeOriginal", "2019:04:03 05:12:44")
			.put(RawMetadata.EXIF, "OffsetTimeOriginal", "+09:00");

		assertThat(map(raw).getDc().getDate()).isEqualTo("2019-04-03T05:12:44+09:00");
	}

	@Test
	void testFilesystemDateIsUsedOnlyWhenOptedIn() {
		RawMetadata raw = new RawMetadata();

		assertThat(MetadataMapper.map(raw, options, "2020-05-05T10:00:00Z").getDc().getDate()).isNull();

		options.setDateFallback(DateFallback.FILESYSTEM);
		AssetMetadata metadata = MetadataMapper.map(raw, options, "2020-05-05T10:00:00Z");
		assertThat(metadata.getDc().getDate()).isEqualTo("2020-05-05T10:00:00Z");
		assertThat(metadata.getProvenance()).containsEntry("dc.date", "file:modified");
	}

	// ---- rights ----

	@Test
	void testRightsPrefersXmpThenIptcThenExif() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "Copyright", "(c) exif")
			.put(RawMetadata.IPTC, "CopyrightNotice", "(c) iptc")
			.put(RawMetadata.XMP, "dc:rights", "(c) xmp");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getDc().getRights()).isEqualTo("(c) xmp");
		assertThat(metadata.getRights().getStatement()).isEqualTo("(c) xmp");

		assertThat(map(new RawMetadata().put(RawMetadata.EXIF, "Copyright", "(c) exif"))
			.getRights().getStatement()).isEqualTo("(c) exif");
	}

	@Test
	void testLicenseUrlPrefersCreativeCommonsThenWebStatementThenTheRightsText() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.XMP, "cc:license", "https://creativecommons.org/licenses/by/4.0/")
			.put(RawMetadata.XMP, "xmpRights:WebStatement", "https://example.org/terms");

		AssetMetadata metadata = map(raw);
		assertThat(metadata.getRights().getLicenseUrl()).isEqualTo("https://creativecommons.org/licenses/by/4.0/");
		assertThat(metadata.getRights().getLicenseId()).isEqualTo("CC-BY-4.0");

		AssetMetadata fromStatement = map(new RawMetadata()
			.put(RawMetadata.XMP, "dc:rights", "CC BY-SA see https://creativecommons.org/licenses/by-sa/4.0/"));
		assertThat(fromStatement.getRights().getLicenseId()).isEqualTo("CC-BY-SA-4.0");
	}

	@Test
	void testLicenceIsNeverGuessedFromFreeText() {
		// A wrong CC-BY is materially worse than a null: the null sends someone to read the rights
		// statement, the wrong value does not.
		AssetMetadata metadata = map(new RawMetadata()
			.put(RawMetadata.XMP, "dc:rights", "Creative Commons Attribution, all rights reserved"));

		assertThat(metadata.getRights().getLicenseId()).isNull();
		assertThat(metadata.getRights().getStatement()).isNotNull();
	}

	@Test
	void testLicenseDetectionCanBeTurnedOff() {
		options.setLicenseDetection(false);
		AssetMetadata metadata = map(new RawMetadata()
			.put(RawMetadata.XMP, "cc:license", "https://creativecommons.org/licenses/by/4.0/"));

		assertThat(metadata.getRights().getLicenseUrl()).isNotNull();
		assertThat(metadata.getRights().getLicenseId()).isNull();
	}

	@Test
	void testMarkedPrefersXmpRightsAndOtherwiseFollowsACopyrightNotice() {
		assertThat(map(new RawMetadata()
			.put(RawMetadata.XMP, "xmpRights:Marked", "false")
			.put(RawMetadata.EXIF, "Copyright", "(c) 2019")).getRights().getMarked()).isFalse();

		assertThat(map(new RawMetadata()
			.put(RawMetadata.EXIF, "Copyright", "(c) 2019")).getRights().getMarked()).isTrue();

		assertThat(map(new RawMetadata()).getRights().getMarked()).isNull();
	}

	// ---- capture ----

	@Test
	void testCapturePrefersExifOverTheXmpMirrorAndNormalisesUnits() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "Make", "SONY")
			.put(RawMetadata.XMP, "tiff:Make", "not this one")
			.put(RawMetadata.EXIF, "ExposureTime", "1/250")
			.put(RawMetadata.EXIF, "FNumber", "f/8.0")
			.put(RawMetadata.EXIF, "FocalLength", "35 mm")
			.put(RawMetadata.EXIF, "ISOSpeedRatings", "100")
			.put(RawMetadata.EXIF, "Orientation", "6");

		CaptureBlock capture = map(raw).getCapture();

		assertThat(capture.getMake()).isEqualTo("SONY");
		assertThat(capture.getExposureTime()).isEqualTo(0.004d, within(1e-9));
		assertThat(capture.getFNumber()).isEqualTo(8.0d);
		assertThat(capture.getFocalLength()).isEqualTo(35.0d);
		assertThat(capture.getIso()).isEqualTo(100);
		assertThat(capture.getOrientation()).isEqualTo(6);
	}

	@Test
	void testFlashAcceptsBothTheBitFieldAndItsDescription() {
		assertThat(MetadataMapper.toFlash("1")).isTrue();
		assertThat(MetadataMapper.toFlash("16")).isFalse();
		assertThat(MetadataMapper.toFlash("Flash did not fire")).isFalse();
		assertThat(MetadataMapper.toFlash("Flash fired, auto mode")).isTrue();
		assertThat(MetadataMapper.toFlash("something else entirely")).isNull();
	}

	// ---- geo ----

	@Test
	void testGeoPrefersTheExifGpsIfdOverTheXmpMirror() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "GPSLatitude", "35.360833")
			.put(RawMetadata.EXIF, "GPSLongitude", "138.727500")
			.put(RawMetadata.XMP, "exif:GPSLatitude", "0.0")
			.put(RawMetadata.XMP, "exif:GPSLongitude", "0.0");

		GeoBlock geo = map(raw).firstGeo();

		assertThat(geo).isNotNull();
		assertThat(geo.getLat()).isEqualTo(35.360833d, within(1e-9));
		assertThat(geo.getMethod()).isEqualTo(RawMetadata.EXIF);
	}

	@Test
	void testGeoFallsBackToXmpAndRecordsItAsTheMethod() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.XMP, "exif:GPSLatitude", "-33.868800")
			.put(RawMetadata.XMP, "exif:GPSLongitude", "151.209300");

		GeoBlock geo = map(raw).firstGeo();

		assertThat(geo.getLat()).isEqualTo(-33.8688d, within(1e-9));
		// The method is the source, never the file format - it is part of the component's identity.
		assertThat(geo.getMethod()).isEqualTo(RawMetadata.XMP);
	}

	@Test
	void testGeoUsesTheSidecarWhenNothingIsEmbedded() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.SIDECAR, "exif:GPSLatitude", "48.8584")
			.put(RawMetadata.SIDECAR, "exif:GPSLongitude", "2.2945");

		assertThat(map(raw).firstGeo().getMethod()).isEqualTo(RawMetadata.SIDECAR);
	}

	@Test
	void testIptcPlaceNamesNeverBecomeACoordinate() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.IPTC, "City", "Fujinomiya")
			.put(RawMetadata.IPTC, "Province-State", "Shizuoka")
			.put(RawMetadata.IPTC, "Country-PrimaryLocationName", "JP");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getGeo()).isEmpty();
		assertThat(metadata.placeLabel()).isEqualTo("Fujinomiya, Shizuoka, JP");
		assertThat(metadata.getDc().getCoverage()).isEqualTo("Fujinomiya, Shizuoka, JP");
	}

	@Test
	void testGpsPolicyRoundsOrDropsTheCoordinate() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "GPSLatitude", "35.360833")
			.put(RawMetadata.EXIF, "GPSLongitude", "138.727500");

		options.setGpsPolicy(GpsPolicy.ROUND).setGpsRoundDecimals(2);
		assertThat(map(raw).firstGeo().getLat()).isEqualTo(35.36d, within(1e-9));

		options.setGpsPolicy(GpsPolicy.DROP);
		assertThat(map(raw).getGeo()).isEmpty();
	}

	@Test
	void testGpsTrackIsDecimatedRatherThanTruncated() {
		// Truncating a two-hour flight to its first N samples is a map pin on the runway.
		List<GeoBlock> samples = new java.util.ArrayList<>();
		for (int i = 0; i < 100; i++) {
			samples.add(new GeoBlock(i, i, RawMetadata.EXIF).setTimeFromMs(i * 1000L));
		}

		MetadataMapper.decimate(samples, 5);

		assertThat(samples).hasSize(5);
		assertThat(samples.get(0).getTimeFromMs()).isZero();
		assertThat(samples.get(4).getTimeFromMs()).isEqualTo(99_000L);
	}

	// ---- language, format and type ----

	@Test
	void testLanguageIsNeverGuessed() {
		assertThat(map(new RawMetadata().put(RawMetadata.CONTAINER, "ContentType", "image/jpeg"))
			.getDc().getLanguage()).isNull();

		assertThat(map(new RawMetadata().put(RawMetadata.XMP, "dc:language", "ja"))
			.getDc().getLanguage()).isEqualTo("ja");
	}

	@Test
	void testFormatIsTheMimeTypeAndTypeIsTheDcmiTerm() {
		// The classic Dublin Core mistake is putting the MIME type in dc:type.
		AssetMetadata metadata = map(new RawMetadata().put(RawMetadata.CONTAINER, "ContentType", "image/jpeg"));

		assertThat(metadata.getDc().getFormat()).isEqualTo("image/jpeg");
		assertThat(metadata.getDc().getType()).isEqualTo("StillImage");

		assertThat(MetadataMapper.dcmiType("video/mp4")).isEqualTo("MovingImage");
		assertThat(MetadataMapper.dcmiType("audio/mpeg")).isEqualTo("Sound");
		assertThat(MetadataMapper.dcmiType("application/pdf")).isEqualTo("Text");
	}

	// ---- MetaLoom's own write-back marker ----

	@Test
	void testMetaloomWrittenValuesNeverOutrankAnAuthoredOne() {
		// Without this rule the write, re-ingest, re-write loop promotes a machine guess to authored
		// ground truth and the catalogue degrades on every pass.
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.METALOOM, "dc:title", "generated caption")
			.put(RawMetadata.EXIF, "ImageDescription", "what the photographer wrote");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getDc().getTitle()).isEqualTo("what the photographer wrote");
	}

	@Test
	void testMetaloomWrittenValuesAreStillIngestedWhenNothingElseExists() {
		RawMetadata raw = new RawMetadata().put(RawMetadata.METALOOM, "dc:title", "generated caption");

		AssetMetadata metadata = map(raw);

		assertThat(metadata.getDc().getTitle()).isEqualTo("generated caption");
		assertThat(metadata.getProvenance()).containsEntry("dc.title", "metaloom:dc:title");
	}

	// ---- envelope contract ----

	@Test
	void testEmptyRawYieldsAnEmptyButWellFormedEnvelope() {
		AssetMetadata metadata = map(new RawMetadata());

		assertThat(metadata.isEmpty()).isTrue();
		var json = metadata.toJson();
		assertThat(json.getInteger("v")).isEqualTo(AssetMetadata.VERSION);
		assertThat(json.getJsonObject("dc")).isNotNull();
		assertThat(json.containsKey("geo")).isFalse();
	}

	@Test
	void testAbsentFieldsAreOmittedRatherThanEmptied() {
		AssetMetadata metadata = map(new RawMetadata().put(RawMetadata.XMP, "dc:title", "only a title"));

		var dc = metadata.toJson().getJsonObject("dc");
		assertThat(dc.getString("title")).isEqualTo("only a title");
		assertThat(dc.containsKey("description")).isFalse();
	}

	@Test
	void testRawIsOmittedUnlessOptedInAndIsCappedWhenItIs() {
		RawMetadata raw = new RawMetadata();
		for (int i = 0; i < 20; i++) {
			raw.put(RawMetadata.EXIF, "Tag" + i, "value-" + i);
		}

		assertThat(map(raw).getRaw()).isEmpty();

		options.setIncludeRaw(true).setRawMaxKeys(5).setRawMaxValueBytes(4);
		AssetMetadata metadata = map(raw);

		// Five entries plus the marker that says the dump was cut short - silent truncation would
		// read as "this is everything the file carried".
		assertThat(metadata.getRaw()).hasSize(6).containsKey("...");
		assertThat(metadata.getRaw().get("exif:Tag0")).isEqualTo("valu…");
	}

	@Test
	void testTextOutputCarriesTheAuthoredProseOnly() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.XMP, "dc:title", "Sunrise over Fuji")
			.put(RawMetadata.XMP, "dc:description", "Taken from the fifth station.")
			.put(RawMetadata.XMP, "dc:subject", "sunrise")
			.put(RawMetadata.XMP, "dc:creator", "Jane Doe")
			.put(RawMetadata.EXIF, "Make", "SONY")
			.put(RawMetadata.EXIF, "ISOSpeedRatings", "100");

		String text = map(raw).toText();

		assertThat(text).contains("Sunrise over Fuji", "Taken from the fifth station.", "sunrise", "Jane Doe");
		assertThat(text).doesNotContain("SONY").doesNotContain("100");
	}

	@Test
	void testSourcesReportWhichStandardsTheFileSpoke() {
		RawMetadata raw = new RawMetadata()
			.put(RawMetadata.EXIF, "Make", "SONY")
			.put(RawMetadata.XMP, "dc:title", "t");

		assertThat(map(raw).getSources()).containsExactlyInAnyOrder(RawMetadata.EXIF, RawMetadata.XMP);
	}
}
