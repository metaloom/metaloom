package io.metaloom.cortex.node.sink.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class S3KeyTemplateTest {

	private static final String ARTIFACT_HASH = "e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931"
		+ "f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629";
	private static final String SOURCE_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
		+ "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private S3KeyTemplate.Values values() {
		return values(0, false);
	}

	private S3KeyTemplate.Values values(int index, boolean multiValued) {
		return new S3KeyTemplate.Values(ARTIFACT_HASH, SOURCE_HASH, "archive", "thumbnail",
			"thumbnail_path", ".thumb", "sheet.thumb", "sheet", "a1b2c3", index, multiValued);
	}

	@Test
	public void testDefaultTemplateRendersAShardedContentAddressedKey() {
		String key = S3KeyTemplate.parse(S3SinkNodeOptions.DEFAULT_KEY_TEMPLATE).render(values());

		assertThat(key).isEqualTo("cortex/thumbnail/thumbnail_path/e7c2/" + ARTIFACT_HASH + ".thumb");
	}

	@Test
	public void testShardMatchesTheLocalBinCacheLevel() {
		// HashUtils.segmentPath shards on the first 4 hex chars; {sha512:4} must agree so the
		// bucket layout mirrors the local *_bin layout.
		String key = S3KeyTemplate.parse("{sha512:4}").render(values());

		assertThat(key).isEqualTo(ARTIFACT_HASH.substring(0, 4));
	}

	@Test
	public void testIndexSuffixIsEmptyForSingleValuedOutputs() {
		S3KeyTemplate template = S3KeyTemplate.parse("a/{basename}{indexSuffix}{ext}");

		assertThat(template.render(values(0, false))).isEqualTo("a/sheet.thumb");
		assertThat(template.render(values(3, true))).isEqualTo("a/sheet-3.thumb");
	}

	@Test
	public void testIndexAlwaysRendersTheNumber() {
		assertThat(S3KeyTemplate.parse("k/{index}").render(values(0, false))).isEqualTo("k/0");
		assertThat(S3KeyTemplate.parse("k/{index}").render(values(7, true))).isEqualTo("k/7");
	}

	@Test
	public void testEveryPlaceholderResolves() {
		String key = S3KeyTemplate.parse(
			"{nodeId}/{sourceNode}/{sourceKey}/{assetUuid}/{filename}/{basename}/{sourceSha512:6}{ext}")
			.render(values());

		assertThat(key).isEqualTo("archive/thumbnail/thumbnail_path/a1b2c3/sheet.thumb/sheet/012345.thumb");
	}

	@Test
	public void testExtensionCarriesItsDot() {
		assertThat(S3KeyTemplate.parse("f{ext}").render(values())).isEqualTo("f.thumb");
	}

	@Test
	public void testExtensionlessArtifactRendersAnEmptyExtension() {
		S3KeyTemplate.Values noExt = new S3KeyTemplate.Values(ARTIFACT_HASH, SOURCE_HASH, "n", "s", "k",
			"", "data", "data", "u", 0, false);

		assertThat(S3KeyTemplate.parse("f/{basename}{ext}").render(noExt)).isEqualTo("f/data");
	}

	// --- parse-time validation ------------------------------------------------------------

	@Test
	public void testUnknownPlaceholderIsRejectedAndNamed() {
		assertThatThrownBy(() -> S3KeyTemplate.parse("a/{bogus}/b"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{bogus}");
	}

	@Test
	public void testInvalidTruncationLengthsAreRejected() {
		assertThatThrownBy(() -> S3KeyTemplate.parse("{sha512:0}"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..128");
		assertThatThrownBy(() -> S3KeyTemplate.parse("{sha512:129}"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..128");
	}

	@Test
	public void testTruncationIsOnlyAllowedOnHashes() {
		assertThatThrownBy(() -> S3KeyTemplate.parse("{nodeId:3}"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not take a length");
	}

	@Test
	public void testEmptyTemplateIsRejected() {
		assertThatThrownBy(() -> S3KeyTemplate.parse(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> S3KeyTemplate.parse(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void testTemplateWithNoPlaceholdersPassesThrough() {
		assertThat(S3KeyTemplate.parse("a/fixed/key").render(values())).isEqualTo("a/fixed/key");
	}

	// --- render-time validation -----------------------------------------------------------

	@Test
	public void testMissingHashFailsRatherThanRenderingNull() {
		// A key containing the literal "null" would make every asset collide onto one object.
		S3KeyTemplate.Values noHash = new S3KeyTemplate.Values(null, null, "n", "s", "k", ".png",
			"a.png", "a", "u", 0, false);

		assertThatThrownBy(() -> S3KeyTemplate.parse("a/{sha512}").render(noHash))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("{sha512}")
			.hasMessageContaining("upstream sha512 node");
	}

	@Test
	public void testMissingAssetUuidFails() {
		S3KeyTemplate.Values offline = new S3KeyTemplate.Values(ARTIFACT_HASH, SOURCE_HASH, "n", "s",
			"k", ".png", "a.png", "a", null, 0, false);

		assertThatThrownBy(() -> S3KeyTemplate.parse("a/{assetUuid}/b").render(offline))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("{assetUuid}");
	}

	@Test
	public void testLeadingSlashIsStrippedAndDoubleSlashesCollapse() {
		assertThat(S3KeyTemplate.parse("/a//b///c").render(values())).isEqualTo("a/b/c");
	}

	@Test
	public void testKeyEndingInSlashIsRejected() {
		// AwsS3ObjectStore.list filters those out as directory placeholders, so the object would be
		// invisible to s3-source forever.
		S3KeyTemplate.Values noExt = new S3KeyTemplate.Values(ARTIFACT_HASH, SOURCE_HASH, "n", "s",
			"k", "", "a", "a", "u", 0, false);

		assertThatThrownBy(() -> S3KeyTemplate.parse("a/b/{ext}").render(noExt))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("directory placeholder");
	}

	@Test
	public void testDotSegmentsAreRejected() {
		assertThatThrownBy(() -> S3KeyTemplate.parse("a/../b").render(values()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("'..'");
		assertThatThrownBy(() -> S3KeyTemplate.parse("a/./b").render(values()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("'.'");
	}

	@Test
	public void testOverlongKeyIsRejected() {
		String longSegment = "x".repeat(1100);

		assertThatThrownBy(() -> S3KeyTemplate.parse(longSegment).render(values()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("1024");
	}

	@Test
	public void testPlaceholdersAreReported() {
		assertThat(S3KeyTemplate.parse(S3SinkNodeOptions.DEFAULT_KEY_TEMPLATE).placeholders())
			.containsExactly("sourceNode", "sourceKey", "sha512", "ext");
	}
}
