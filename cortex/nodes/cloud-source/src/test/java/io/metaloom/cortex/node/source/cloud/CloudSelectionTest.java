package io.metaloom.cortex.node.source.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.fs.FileState;

public class CloudSelectionTest {

	private static CloudSelection selection(Set<String> suffixes, Set<String> mimeTypes) {
		return new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0, suffixes, mimeTypes,
			CloudSelection.DEFAULT_EMIT_STATES, true, false, false);
	}

	private static CloudFileRef file(String name, String mime, boolean folder, boolean trashed) {
		return new CloudFileRef(CloudProviderId.GDRIVE, "d", "f1", name, null, mime, "t", 1, 0,
			folder, trashed, null, true);
	}

	@Test
	public void testSuffixParsingIsForgiving() {
		assertThat(CloudSelection.parseSuffixes("mp4, .MKV ,jpg")).containsExactly("mp4", "mkv", "jpg");
		assertThat(CloudSelection.parseSuffixes(null)).isEmpty();
		assertThat(CloudSelection.parseSuffixes("  ")).isEmpty();
	}

	@Test
	public void testMimeParsingLowercases() {
		assertThat(CloudSelection.parseMimeTypes("Video/, IMAGE/PNG")).containsExactly("video/", "image/png");
	}

	@Test
	public void testStateParsingSkipsUnknownEntries() {
		// Options validation rejects these earlier with a better message.
		assertThat(CloudSelection.parseStates(List.of("new", "NONSENSE", "moved")))
			.containsExactlyInAnyOrder(FileState.NEW, FileState.MOVED);
		assertThat(CloudSelection.parseStates(null)).isEmpty();
	}

	@Test
	public void testAnEmptyFilterAcceptsEverything() {
		CloudSelection selection = selection(Set.of(), Set.of());

		assertThat(selection.accepts(file("anything", "application/octet-stream", false, false))).isTrue();
	}

	@Test
	public void testSuffixFilterIsCaseInsensitive() {
		CloudSelection selection = selection(Set.of("mp4"), Set.of());

		assertThat(selection.accepts(file("clip.MP4", "video/mp4", false, false))).isTrue();
		assertThat(selection.accepts(file("notes.txt", "text/plain", false, false))).isFalse();
		assertThat(selection.accepts(file("no-extension", "video/mp4", false, false))).isFalse();
	}

	@Test
	public void testMimeFilterMatchesOnPrefix() {
		CloudSelection selection = selection(Set.of(), Set.of("video/"));

		assertThat(selection.accepts(file("clip", "video/mp4", false, false))).isTrue();
		assertThat(selection.accepts(file("shot", "image/jpeg", false, false))).isFalse();
	}

	@Test
	public void testBothFiltersMustPass() {
		CloudSelection selection = selection(Set.of("mp4"), Set.of("video/"));

		assertThat(selection.accepts(file("clip.mp4", "video/mp4", false, false))).isTrue();
		// A .mp4 that is really something else is rejected - which a suffix filter alone could not do.
		assertThat(selection.accepts(file("clip.mp4", "text/plain", false, false))).isFalse();
	}

	@Test
	public void testFoldersAreNeverAccepted() {
		assertThat(selection(Set.of(), Set.of()).accepts(file("Videos", null, true, false))).isFalse();
	}

	@Test
	public void testTrashedFilesAreRejectedUnlessRequested() {
		assertThat(selection(Set.of(), Set.of()).accepts(file("a.mp4", "video/mp4", false, true))).isFalse();

		CloudSelection including = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of(), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, true, true, false);
		assertThat(including.accepts(file("a.mp4", "video/mp4", false, true))).isTrue();
	}

	@Test
	public void testDefaultEmitStatesIncludeMovedUnlikeS3() {
		// A cloud file id survives a move, so offering MOVED is honest here.
		assertThat(CloudSelection.DEFAULT_EMIT_STATES)
			.containsExactlyInAnyOrder(FileState.NEW, FileState.MODIFIED, FileState.MOVED);
	}

	@Test
	public void testMayDescendHonoursRecursionAndDepth() {
		CloudSelection unlimited = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of(), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, true, false, false);
		assertThat(unlimited.mayDescend(50)).isTrue();

		CloudSelection shallow = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 2,
			Set.of(), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, true, false, false);
		assertThat(shallow.mayDescend(1)).isTrue();
		assertThat(shallow.mayDescend(2)).isFalse();

		CloudSelection flat = new CloudSelection(CloudProviderId.GDRIVE, "d", null, false, 0,
			Set.of(), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, true, false, false);
		assertThat(flat.mayDescend(0)).isFalse();
	}

	@Test
	public void testBlankFolderIdNormalisesToNull() {
		CloudSelection selection = new CloudSelection(CloudProviderId.GDRIVE, "d", "  ", true, 0,
			Set.of(), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, true, false, false);

		assertThat(selection.folderId()).isNull();
	}

	@Test
	public void testEmptyEmitStatesFallsBackToTheDefault() {
		CloudSelection selection = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of(), Set.of(), Set.of(), true, false, false);

		assertThat(selection.emitStates()).containsExactlyInAnyOrderElementsOf(CloudSelection.DEFAULT_EMIT_STATES);
	}

	@Test
	public void testADriveIdIsRequired() {
		assertThatThrownBy(() -> new CloudSelection(CloudProviderId.GDRIVE, " ", null, true, 0,
			Set.of(), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, true, false, false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("drive id");
	}
}
