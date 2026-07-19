package io.metaloom.cortex.node.source.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class FilesystemSourceNodeOptionsTest {

	@Test
	public void testDefaultsAreValid() {
		assertThat(new FilesystemSourceNodeOptions().validate().isInvalid()).isFalse();
	}

	@Test
	public void testEnabledByDefault() {
		assertThat(new FilesystemSourceNodeOptions().isEnabled()).isTrue();
	}

	@Test
	public void testNegativeTimeoutIsRejected() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions();
		options.setTimeoutMs(-1);

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors()).anyMatch(e -> e.contains("timeoutMs"));
	}

	@Test
	public void testBlankGlobEntryIsRejected() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions()
			.setPathGlobs(Arrays.asList("/media/**.mp4", "   "));

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors()).anyMatch(e -> e.contains("pathGlobs"));
	}

	@Test
	public void testNullGlobListIsNormalisedToEmpty() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions().setPathGlobs(null);

		assertThat(options.getPathGlobs()).isEmpty();
		assertThat(options.validate().isInvalid()).isFalse();
	}

	@Test
	public void testValidGlobsAreAccepted() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions()
			.setPathGlobs(List.of("/media/**.mp4", "/archive/*.mkv"))
			.setPath("/media");

		assertThat(options.validate().isInvalid()).isFalse();
		assertThat(options.getPath()).isEqualTo("/media");
		assertThat(options.getPathGlobs()).hasSize(2);
	}

	@Test
	public void testEmitStatesDefaultToNewModifiedMoved() {
		assertThat(new FilesystemSourceNodeOptions().getEmitStates())
			.containsExactlyInAnyOrder("NEW", "MODIFIED", "MOVED");
	}

	@Test
	public void testValidEmitStatesAreAccepted() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions()
			.setEmitStates(List.of("NEW", "DELETED"));

		assertThat(options.validate().isInvalid()).isFalse();
	}

	@Test
	public void testUnknownEmitStateIsRejected() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions()
			.setEmitStates(List.of("NEW", "BOGUS"));

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors()).anyMatch(e -> e.contains("emitStates"));
	}

	@Test
	public void testNullEmitStatesIsNormalisedToDefaults() {
		FilesystemSourceNodeOptions options = new FilesystemSourceNodeOptions().setEmitStates(null);

		assertThat(options.getEmitStates()).containsExactlyInAnyOrder("NEW", "MODIFIED", "MOVED");
		assertThat(options.validate().isInvalid()).isFalse();
	}
}
