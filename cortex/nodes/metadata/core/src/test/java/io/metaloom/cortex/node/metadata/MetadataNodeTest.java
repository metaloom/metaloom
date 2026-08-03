package io.metaloom.cortex.node.metadata;

import static io.metaloom.cortex.node.metadata.assertj.MetadataNodeAssertions.assertThat;
import static io.metaloom.cortex.node.metadata.assertj.MetadataNodeAssertions.within;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.metadata.fixture.ExifJpegFixture;
import io.metaloom.cortex.node.metadata.fixture.XmpFixture;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonObject;

/**
 * The node against real bytes: a JPEG whose EXIF, GPS and XMP are written by
 * {@link ExifJpegFixture}, so the reader, the mapper and the port contract are all exercised
 * together rather than mocked apart.
 */
class MetadataNodeTest {

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;

	@BeforeEach
	void setup() {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
	}

	private MetadataNode node() {
		return node(new MetadataNodeOptions());
	}

	private MetadataNode node(MetadataNodeOptions options) {
		// A null client is offline mode: the node computes and emits, and persists nothing.
		return new MetadataNode(null, cortexOptions, options);
	}

	private StubLoomMedia image(String name, byte[] content) {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, name, content);
		return new StubLoomMedia(backing.file().getAbsolutePath(), false, true, false, false);
	}

	private static byte[] photo() throws Exception {
		return ExifJpegFixture.builder()
			.make("SONY")
			.model("ILCE-7M3")
			.imageDescription("a caption the camera wrote")
			.artist("Exif Artist")
			.copyright("(c) 2019 Jane Doe")
			.software("Lightroom 12.3")
			.orientation(1)
			.dateTimeOriginal("2019:04:03 05:12:44")
			.offsetTimeOriginal("+09:00")
			.exposureTime(1, 250)
			.fNumber(80, 10)
			.iso(100)
			.focalLength(35, 1)
			.flash(0)
			.lensModel("FE 24-70mm F2.8 GM")
			.gps(35.360833, 138.727500)
			.altitude(2305)
			.imgDirection(271.5)
			.positioningError(12)
			.build();
	}

	@Test
	void testEmitsTheEnvelopeAndTheTextPort() throws Exception {
		NodeResult result = node().process(NodeContext.create(image("fuji.jpg", photo())));

		assertThat(result).isSuccess();
		assertThat(result).hasOutput(MetadataNode.OUT_METADATA);
		assertThat(result).hasOutput(MetadataNode.OUT_TEXT);
	}

	@Test
	void testReadsExifCaptureSettingsAndNormalisesTheirUnits() throws Exception {
		JsonObject envelope = envelopeOf(node().process(NodeContext.create(image("fuji.jpg", photo()))));
		JsonObject capture = envelope.getJsonObject("capture");

		assertThat(capture.getString("make")).isEqualTo("SONY");
		assertThat(capture.getString("model")).isEqualTo("ILCE-7M3");
		assertThat(capture.getString("lens")).isEqualTo("FE 24-70mm F2.8 GM");
		assertThat(capture.getDouble("exposureTime")).isEqualTo(0.004d, within(1e-6));
		assertThat(capture.getDouble("fNumber")).isEqualTo(8.0d, within(1e-6));
		assertThat(capture.getInteger("iso")).isEqualTo(100);
		assertThat(capture.getBoolean("flash")).isFalse();
		// EXIF states no timezone of its own; the file's OffsetTimeOriginal supplies one.
		assertThat(envelope.getJsonObject("dc").getString("date"))
			.isEqualTo("2019-04-03T05:12:44+09:00");
	}

	@Test
	void testReadsTheGpsIfdAsSignedDecimalDegrees() throws Exception {
		NodeResult result = node().process(NodeContext.create(image("fuji.jpg", photo())));
		JsonObject geo = envelopeOf(result).getJsonObject("geo");

		assertThat(geo.getDouble("lat")).isEqualTo(35.360833d, within(1e-5));
		assertThat(geo.getDouble("lon")).isEqualTo(138.7275d, within(1e-5));
		assertThat(geo.getDouble("altitudeM")).isEqualTo(2305d, within(0.5));
		assertThat(geo.getString("method")).isEqualTo(RawMetadata.EXIF);
		assertThat(result).hasOutput(MetadataNode.OUT_GEO);
	}

	@Test
	void testSouthernAndWesternHemispheresKeepTheirSign() throws Exception {
		byte[] sydney = ExifJpegFixture.builder().gps(-33.8688, 151.2093).build();

		JsonObject geo = envelopeOf(node().process(NodeContext.create(image("sydney.jpg", sydney))))
			.getJsonObject("geo");

		assertThat(geo.getDouble("lat")).isEqualTo(-33.8688d, within(1e-4));
		assertThat(geo.getDouble("lon")).isEqualTo(151.2093d, within(1e-4));
	}

	@Test
	void testEmbeddedXmpBeatsTheExifCaption() throws Exception {
		byte[] edited = ExifJpegFixture.builder()
			.imageDescription("a caption the camera wrote")
			.artist("Exif Artist")
			.xmp(XmpFixture.titled("Sunrise over Fuji", "Taken from the fifth station.", "Jane Doe"))
			.build();

		JsonObject dc = envelopeOf(node().process(NodeContext.create(image("edited.jpg", edited))))
			.getJsonObject("dc");

		assertThat(dc.getString("title")).isEqualTo("Sunrise over Fuji");
		assertThat(dc.getJsonArray("creator")).containsExactly("Jane Doe");
		assertThat(dc.getJsonArray("subject")).contains("sunrise", "mountain");
	}

	@Test
	void testRecognisesAKnownLicenceUrl() throws Exception {
		byte[] licensed = ExifJpegFixture.builder()
			.xmp(XmpFixture.titled("t", "d", "c"))
			.build();

		JsonObject rights = envelopeOf(node().process(NodeContext.create(image("cc.jpg", licensed))))
			.getJsonObject("rights");

		assertThat(rights.getString("licenseId")).isEqualTo("CC-BY-4.0");
	}

	@Test
	void testReadsAnXmpSidecarSittingNextToTheMedia() throws Exception {
		StubLoomMedia media = image("with-sidecar.jpg", ExifJpegFixture.builder().make("SONY").build());
		Files.writeString(tempDir.toPath().resolve("with-sidecar.xmp"),
			XmpFixture.sidecarTitle("Titled by the sidecar"), StandardCharsets.UTF_8);

		JsonObject dc = envelopeOf(node().process(NodeContext.create(media))).getJsonObject("dc");

		assertThat(dc.getString("title")).isEqualTo("Titled by the sidecar");
	}

	@Test
	void testAMissingSidecarIsNormal() throws Exception {
		NodeResult result = node().process(NodeContext.create(image("lonely.jpg", photo())));

		// An object-store-backed media item has no sibling file at all. A miss must never fail.
		assertThat(result).isSuccess();
	}

	@Test
	void testAFileWithNoMetadataSucceedsWithAnEmptyEnvelope() throws Exception {
		// SKIPPED would mean "this item did not need processing". A stripped image was processed;
		// the answer is simply that it says nothing about itself.
		byte[] bare = ExifJpegFixture.builder().build();

		NodeResult result = node().process(NodeContext.create(image("bare.jpg", bare)));

		assertThat(result).isSuccess();
		JsonObject envelope = envelopeOf(result);
		assertThat(envelope.getInteger("v")).isEqualTo(AssetMetadata.VERSION);
		assertThat(envelope.containsKey("geo")).isFalse();
	}

	@Test
	void testAnUnparsableFileFails() throws Exception {
		StubLoomMedia media = image("broken.jpg", "this is not a jpeg at all".getBytes(StandardCharsets.UTF_8));

		NodeResult result = node().process(NodeContext.create(media));

		// Failure is abort(), never next(): NodeContextImpl.next() ignores a recorded failure cause
		// and would report SUCCESS.
		assertThat(result).isFailed();
	}

	@Test
	void testNonProcessableMediaSelfSkips() throws Exception {
		StubLoomMedia other = StubLoomMedia.ofBytes(tempDir, "archive.bin", "not media");

		assertThat(node().process(NodeContext.create(other))).isSkipped();
	}

	@Test
	void testASecondRunIsServedFromTheCache() throws Exception {
		MetadataNode node = node();
		StubLoomMedia media = image("cached.jpg", photo());

		NodeResult first = node.process(NodeContext.create(media));
		NodeResult second = node.process(NodeContext.create(media));

		// A cache hit is SUCCESS with the same outputs, not a skip: it re-emits from the cached
		// envelope rather than dropping the ports. That it also skips re-persisting is asserted in
		// MetadataNodePersistenceTest, where a mocked client can count the writes.
		assertThat(second).isSuccess();
		assertThat(second).hasOutput(MetadataNode.OUT_METADATA);
		assertThat(second.get(MetadataNode.OUT_METADATA)).isEqualTo(first.get(MetadataNode.OUT_METADATA));
	}

	@Test
	void testTheCacheKeyIncludesTheOptionsSoTwoConfigurationsDoNotShareAnswer() throws Exception {
		StubLoomMedia media = image("policy.jpg", photo());

		MetadataNode keeping = node();
		MetadataNode rounding = node(new MetadataNodeOptions().setGpsPolicy(GpsPolicy.ROUND).setGpsRoundDecimals(1));

		double exact = envelopeOf(keeping.process(NodeContext.create(media))).getJsonObject("geo").getDouble("lat");
		double rounded = envelopeOf(rounding.process(NodeContext.create(media))).getJsonObject("geo").getDouble("lat");

		assertThat(exact).isEqualTo(35.360833d, within(1e-5));
		assertThat(rounded).isEqualTo(35.4d, within(1e-9));
	}

	@Test
	void testGpsPolicyDropRemovesTheCoordinateEntirely() throws Exception {
		MetadataNode node = node(new MetadataNodeOptions().setGpsPolicy(GpsPolicy.DROP));

		NodeResult result = node.process(NodeContext.create(image("dropped.jpg", photo())));

		assertThat(envelopeOf(result).containsKey("geo")).isFalse();
		assertThat(result).hasNoOutput(MetadataNode.OUT_GEO);
	}

	@Test
	void testExcludedKeysAreDroppedBeforeAnythingReadsThem() throws Exception {
		MetadataNode node = node(new MetadataNodeOptions()
			.setIncludeRaw(true)
			.setExcludeKeys(java.util.List.of("exif:Make")));

		JsonObject envelope = envelopeOf(node.process(NodeContext.create(image("excluded.jpg", photo()))));

		assertThat(envelope.getJsonObject("capture").containsKey("make")).isFalse();
		assertThat(envelope.getJsonObject("raw").containsKey("exif:Make")).isFalse();
	}

	@Test
	void testTextPortCanBeTurnedOff() throws Exception {
		MetadataNode node = node(new MetadataNodeOptions().setEmitText(false));

		NodeResult result = node.process(NodeContext.create(image("quiet.jpg", photo())));

		assertThat(result).hasNoOutput(MetadataNode.OUT_TEXT);
	}

	private static JsonObject envelopeOf(NodeResult result) {
		return new JsonObject(result.get(MetadataNode.OUT_METADATA));
	}
}
