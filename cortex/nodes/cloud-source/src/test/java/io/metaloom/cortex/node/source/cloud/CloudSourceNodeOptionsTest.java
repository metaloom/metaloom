package io.metaloom.cortex.node.source.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.cortex.cloud.CloudProviderId;

public class CloudSourceNodeOptionsTest {

	@Test
	public void testDefaultsAreValidForBothProviders() {
		assertThat(new GDriveSourceNodeOptions().validate().isValid()).isTrue();
		assertThat(new OneDriveSourceNodeOptions().validate().isValid()).isTrue();
	}

	@Test
	public void testBothAreEnabledByDefault() {
		assertThat(new GDriveSourceNodeOptions().isEnabled()).isTrue();
		assertThat(new OneDriveSourceNodeOptions().isEnabled()).isTrue();
	}

	@Test
	public void testTheKeyMatchesTheDescriptorKind() {
		// The KEY, the node kind, the descriptor kind and the registrar's registration must all be
		// the same string or the node is unreachable from one direction or another.
		assertThat(GDriveSourceNodeOptions.KEY).isEqualTo(CloudProviderId.GDRIVE.kind());
		assertThat(OneDriveSourceNodeOptions.KEY).isEqualTo(CloudProviderId.ONEDRIVE.kind());
	}

	@Test
	public void testDefaultsFavourDeltaAndRecursion() {
		GDriveSourceNodeOptions options = new GDriveSourceNodeOptions();

		assertThat(options.isUseDelta()).isTrue();
		assertThat(options.isRecursive()).isTrue();
		assertThat(options.getMaxDepth()).isZero();
		assertThat(options.isIncludeTrashed()).isFalse();
	}

	@Test
	public void testDefaultEmitStatesIncludeMoved() {
		assertThat(new GDriveSourceNodeOptions().getEmitStates())
			.containsExactly("NEW", "MODIFIED", "MOVED");
	}

	@Test
	public void testAnUnknownEmitStateIsReported() {
		ValidationResult result = new GDriveSourceNodeOptions().setEmitStates(List.of("NEW", "NONSENSE")).validate();

		assertThat(result.isInvalid()).isTrue();
		assertThat(result.getErrors()).anyMatch(error -> error.contains("unknown file state"));
	}

	@Test
	public void testANegativeMaxDepthIsReported() {
		ValidationResult result = new GDriveSourceNodeOptions().setMaxDepth(-1).validate();

		assertThat(result.isInvalid()).isTrue();
		assertThat(result.getErrors()).anyMatch(error -> error.contains("maxDepth"));
	}

	@Test
	public void testAPathInsteadOfAnIdIsReported() {
		assertThat(new GDriveSourceNodeOptions().setFolderId("/some/path").validate().isInvalid()).isTrue();
		assertThat(new GDriveSourceNodeOptions().setDriveId("a/b").validate().isInvalid()).isTrue();
	}

	@Test
	public void testANegativeTimeoutIsReported() {
		GDriveSourceNodeOptions options = new GDriveSourceNodeOptions();
		options.setTimeoutMs(-1);

		assertThat(options.validate().isInvalid()).isTrue();
	}

	@Test
	public void testNullEmitStatesFallsBackToTheDefault() {
		assertThat(new GDriveSourceNodeOptions().setEmitStates(null).getEmitStates())
			.containsExactly("NEW", "MODIFIED", "MOVED");
	}

	/**
	 * A Google-only option on the OneDrive node is an error rather than a silent no-op: every
	 * OneDrive item has downloadable bytes, so there is nothing to export.
	 */
	@Test
	public void testExportNativeDocsOnOneDriveIsReported() {
		ValidationResult result = new OneDriveSourceNodeOptions().setExportNativeDocs(true).validate();

		assertThat(result.isInvalid()).isTrue();
		assertThat(result.getErrors()).anyMatch(error -> error.contains("Google Drive option"));
	}

	@Test
	public void testExportNativeDocsIsValidOnGoogle() {
		assertThat(new GDriveSourceNodeOptions().setExportNativeDocs(true).validate().isValid()).isTrue();
	}

	@Test
	public void testProviderIsReported() {
		assertThat(new GDriveSourceNodeOptions().provider()).isEqualTo(CloudProviderId.GDRIVE);
		assertThat(new OneDriveSourceNodeOptions().provider()).isEqualTo(CloudProviderId.ONEDRIVE);
	}
}
