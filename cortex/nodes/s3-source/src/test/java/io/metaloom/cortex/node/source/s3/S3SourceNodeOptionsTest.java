package io.metaloom.cortex.node.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.fs.FileState;

public class S3SourceNodeOptionsTest {

	@Test
	public void testDefaultsAreValid() {
		assertThat(new S3SourceNodeOptions().validate().isInvalid()).isFalse();
	}

	@Test
	public void testEnabledByDefault() {
		assertThat(new S3SourceNodeOptions().isEnabled()).isTrue();
	}

	@Test
	public void testDefaultEmitStatesExcludeMoved() {
		// S3 has no inode, so the scanner cannot distinguish a rename from a delete plus an add
		// and never emits MOVED. Defaulting to it would silently emit nothing.
		assertThat(new S3SourceNodeOptions().getEmitStates())
			.containsExactly(FileState.NEW.name(), FileState.MODIFIED.name());
	}

	@Test
	public void testUnknownEmitStateIsRejected() {
		S3SourceNodeOptions options = new S3SourceNodeOptions().setEmitStates(List.of("NEW", "BOGUS"));

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors().get(0)).contains("unknown file state").contains("BOGUS");
	}

	@Test
	public void testMovedIsStillAcceptedForSymmetryWithFilesystemSource() {
		assertThat(new S3SourceNodeOptions().setEmitStates(List.of("MOVED")).validate().isInvalid()).isFalse();
	}

	@Test
	public void testBucketMustNotBeAPath() {
		S3SourceNodeOptions options = new S3SourceNodeOptions().setBucket("media/2026");

		assertThat(options.validate().isInvalid()).isTrue();
		assertThat(options.validate().getErrors().get(0)).contains("not a path");
	}

	@Test
	public void testNullEmitStatesFallsBackToTheDefault() {
		assertThat(new S3SourceNodeOptions().setEmitStates(null).getEmitStates())
			.isEqualTo(S3SourceNodeOptions.DEFAULT_EMIT_STATES);
	}

	@Test
	public void testSuffixParsingIsForgiving() {
		assertThat(S3Selection.parseSuffixes("mp4, .MKV ,jpg,")).containsExactly("mp4", "mkv", "jpg");
		assertThat(S3Selection.parseSuffixes(null)).isEmpty();
		assertThat(S3Selection.parseSuffixes("  ")).isEmpty();
	}

	@Test
	public void testSuffixFilterMatchesCaseInsensitively() {
		S3Selection selection = new S3Selection("b", null, Set.of("mp4"), null, false, false);

		assertThat(selection.accepts("a/clip.MP4")).isTrue();
		assertThat(selection.accepts("a/clip.mp4")).isTrue();
		assertThat(selection.accepts("a/clip.txt")).isFalse();
		assertThat(selection.accepts("a/clip")).isFalse();
	}

	@Test
	public void testEmptySuffixSetAcceptsEverything() {
		S3Selection selection = new S3Selection("b", null, Set.of(), null, false, false);

		assertThat(selection.accepts("a/clip.anything")).isTrue();
		assertThat(selection.accepts("a/no-extension")).isTrue();
	}

	@Test
	public void testSelectionRequiresABucket() {
		assertThat(new S3SourceNodeOptions().setBucket(null).validate().isInvalid()).isFalse();
		// ... but building a selection without one is a hard error.
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> new S3Selection(" ", null, Set.of(), null, false, false))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bucket");
	}

	@Test
	public void testBlankPrefixNormalisesToNull() {
		assertThat(new S3Selection("b", "  ", Set.of(), null, false, false).prefix()).isNull();
	}
}
