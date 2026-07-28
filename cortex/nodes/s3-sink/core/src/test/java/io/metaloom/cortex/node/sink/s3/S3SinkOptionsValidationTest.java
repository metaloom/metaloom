package io.metaloom.cortex.node.sink.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

public class S3SinkOptionsValidationTest {

	@Test
	public void testDefaultsAreValid() {
		assertThat(new S3SinkNodeOptions().validate().isInvalid()).isFalse();
	}

	@Test
	public void testDefaultsDoNotRequireABucket() {
		// Load-bearing: RegistryNodeRegistrar validates the WORKER's options for every node it
		// builds, and the bucket belongs on the node definition. Requiring it here would make
		// every s3-sink in every pipeline fail to build with a misleading message.
		assertThat(new S3SinkNodeOptions().getBucket()).isNull();
		assertThat(new S3SinkNodeOptions().validate().isInvalid()).isFalse();
	}

	@Test
	public void testEnabledByDefault() {
		assertThat(new S3SinkNodeOptions().isEnabled()).isTrue();
	}

	@Test
	public void testDeleteAfterUploadIsOffByDefault() {
		// scene-layout reads depthmap_path off the same worker's disk.
		assertThat(new S3SinkNodeOptions().isDeleteAfterUpload()).isFalse();
	}

	@Test
	public void testAutoDiscoverAndCreateAssetsAreOnByDefault() {
		assertThat(new S3SinkNodeOptions().isAutoDiscover()).isTrue();
		assertThat(new S3SinkNodeOptions().isCreateAssets()).isTrue();
		assertThat(new S3SinkNodeOptions().isFailOnPartial()).isTrue();
		assertThat(new S3SinkNodeOptions().isIncludeSource()).isFalse();
	}

	@Test
	public void testInvalidBucketNameIsRejected() {
		assertThat(new S3SinkNodeOptions().setBucket("media/sub").validate().isInvalid()).isTrue();
		assertThat(new S3SinkNodeOptions().setBucket("a").validate().isInvalid()).isTrue();
		assertThat(new S3SinkNodeOptions().setBucket("valid-bucket.name").validate().isInvalid()).isFalse();
	}

	@Test
	public void testInvalidKeyTemplateIsRejectedWithTheOffendingPlaceholder() {
		S3SinkNodeOptions options = new S3SinkNodeOptions().setKeyTemplate("a/{nope}");

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors().get(0)).contains("keyTemplate").contains("{nope}");
	}

	@Test
	public void testBlankKeyTemplateFallsBackToTheDefault() {
		assertThat(new S3SinkNodeOptions().setKeyTemplate("  ").getKeyTemplate())
			.isEqualTo(S3SinkNodeOptions.DEFAULT_KEY_TEMPLATE);
	}

	@Test
	public void testMalformedArtifactEntryIsRejected() {
		S3SinkNodeOptions options = new S3SinkNodeOptions().setArtifacts(List.of("thumbnail"));

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors().get(0)).contains("nodeId:outputKey");
	}

	@Test
	public void testWellFormedArtifactEntriesPass() {
		assertThat(new S3SinkNodeOptions()
			.setArtifacts(List.of("thumbnail:thumbnail_path", "script:frames"))
			.validate().isInvalid()).isFalse();
	}

	@Test
	public void testANodeThatCouldNeverUploadAnythingIsRejected() {
		S3SinkNodeOptions options = new S3SinkNodeOptions().setAutoDiscover(false);

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors().get(0)).contains("never upload anything");
	}

	@Test
	public void testIncludeSourceAloneIsAValidConfiguration() {
		// An archiver: no upstream artifacts, just the media item.
		assertThat(new S3SinkNodeOptions().setAutoDiscover(false).setIncludeSource(true)
			.validate().isInvalid()).isFalse();
	}

	@Test
	public void testNonPositiveLimitsAreRejected() {
		assertThat(new S3SinkNodeOptions().setMaxArtifacts(0).validate().isInvalid()).isTrue();
		assertThat(new S3SinkNodeOptions().setMaxArtifactBytes(-1).validate().isInvalid()).isTrue();
		assertThat(new S3SinkNodeOptions().setMaxArtifactBytes(0).validate().isInvalid()).isFalse();
	}

	@Test
	public void testOverwritePolicyParsing() {
		assertThat(OverwritePolicy.parse(null)).isEqualTo(OverwritePolicy.IF_DIFFERENT);
		assertThat(OverwritePolicy.parse("  ")).isEqualTo(OverwritePolicy.IF_DIFFERENT);
		assertThat(OverwritePolicy.parse("always")).isEqualTo(OverwritePolicy.ALWAYS);
		assertThat(OverwritePolicy.parse("NEVER")).isEqualTo(OverwritePolicy.NEVER);
		assertThatThrownBy(() -> OverwritePolicy.parse("sometimes"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NEVER, IF_DIFFERENT, ALWAYS");
	}

	@Test
	public void testNullCollectionsNormalise() {
		assertThat(new S3SinkNodeOptions().setArtifacts(null).getArtifacts()).isEmpty();
		assertThat(new S3SinkNodeOptions().setOverwrite(null).getOverwrite()).isEqualTo(OverwritePolicy.IF_DIFFERENT);
	}
}
