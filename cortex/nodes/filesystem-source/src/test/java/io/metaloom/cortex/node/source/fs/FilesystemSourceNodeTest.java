package io.metaloom.cortex.node.source.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.fs.FileState;

public class FilesystemSourceNodeTest {

	@TempDir
	Path root;

	@TempDir
	Path indexDir;

	private LoomMediaLoader mediaLoader;

	private Path videoA;
	private Path videoB;

	@BeforeEach
	public void setup() throws IOException {
		videoA = Files.writeString(root.resolve("a.mp4"), "a");
		videoB = Files.writeString(root.resolve("b.mp4"), "b");

		mediaLoader = mock(LoomMediaLoader.class);
		when(mediaLoader.load(any(Path.class))).thenAnswer(inv -> {
			Path path = inv.getArgument(0);
			LoomMedia media = mock(LoomMedia.class);
			when(media.path()).thenReturn(path);
			when(media.absolutePath()).thenReturn(path.toString());
			return media;
		});
	}

	private FilesystemSourceNode rootNode() {
		return new FilesystemSourceNode("fs-source", mediaLoader, root, List.of(), null, indexDir);
	}

	private FilesystemSourceNode rootNode(Set<FileState> emitStates) {
		return new FilesystemSourceNode("fs-source", mediaLoader, root, List.of(), emitStates, indexDir);
	}

	@Test
	public void testNodeIsMarkedAsSource() {
		assertThat(rootNode().isSource()).isTrue();
	}

	// --- Initial scan ------------------------------------------------------

	@Test
	public void testInitialScanEmitsAllFilesAsNew() {
		List<Path> emitted = rootNode().stream()
			.map(LoomMedia::path)
			.toList()
			.blockingGet();

		assertThat(emitted).containsExactlyInAnyOrder(videoA, videoB);
	}

	@Test
	public void testStreamUsesGlobsInPreferenceToRoot() {
		FilesystemSourceNode node = new FilesystemSourceNode("fs-source", mediaLoader, root,
			List.of(root + "/a.mp4"), null, indexDir);

		List<Path> emitted = node.stream().map(LoomMedia::path).toList().blockingGet();

		assertThat(emitted).containsExactly(videoA);
	}

	@Test
	public void testStreamIsColdAndDoesNoWorkBeforeSubscription() throws IOException {
		FilesystemSourceNode node = rootNode();

		// Building the stream must not touch the filesystem — a file added
		// after assembly but before subscription still has to be picked up.
		var stream = node.stream();
		Path late = Files.writeString(root.resolve("c.mp4"), "c");

		List<Path> emitted = stream.map(LoomMedia::path).toList().blockingGet();
		assertThat(emitted).contains(late);
	}

	// --- Differential behavior --------------------------------------------

	@Test
	public void testUnchangedRerunEmitsNothing() {
		FilesystemSourceNode node = rootNode();
		assertThat(node.stream().count().blockingGet()).isEqualTo(2);
		// Nothing changed - the persisted index makes the second run a no-op.
		assertThat(node.stream().count().blockingGet()).isZero();
	}

	@Test
	public void testDetectsAddedFile() throws IOException {
		FilesystemSourceNode node = rootNode();
		node.stream().count().blockingGet();

		Path c = Files.writeString(root.resolve("c.mp4"), "c");

		List<Path> emitted = node.stream().map(LoomMedia::path).toList().blockingGet();
		assertThat(emitted).containsExactly(c);
	}

	@Test
	public void testDetectsModifiedFile() throws IOException {
		FilesystemSourceNode node = rootNode();
		node.stream().count().blockingGet();

		Files.writeString(root.resolve("a.mp4"), "a much longer content changing the size");

		List<Path> emitted = node.stream().map(LoomMedia::path).toList().blockingGet();
		assertThat(emitted).containsExactly(videoA);
	}

	@Test
	public void testEmitStatesCanSelectRemovedFilesOnly() throws IOException {
		FilesystemSourceNode node = rootNode(Set.of(FileState.DELETED));
		// First run: a and b are NEW but only DELETED is emitted -> nothing.
		assertThat(node.stream().count().blockingGet()).isZero();

		Files.delete(videoB);

		List<Path> emitted = node.stream().map(LoomMedia::path).toList().blockingGet();
		assertThat(emitted).containsExactly(videoB);
	}

	@Test
	public void testEmitStatesEverythingKeepsEmittingPresentFiles() {
		FilesystemSourceNode node = rootNode(Set.of(
			FileState.NEW, FileState.MODIFIED, FileState.MOVED, FileState.PRESENT, FileState.DELETED));

		assertThat(node.stream().count().blockingGet()).isEqualTo(2);
		// With PRESENT included, an unchanged re-run still emits both files.
		assertThat(node.stream().count().blockingGet()).isEqualTo(2);
	}

	@Test
	public void testStreamOnMissingRootCompletesWithoutEmitting() {
		FilesystemSourceNode node = new FilesystemSourceNode("fs-source", mediaLoader,
			root.resolve("does-not-exist"), List.of(), null, indexDir);

		assertThat(node.stream().count().blockingGet()).isZero();
	}

	// --- process() ---------------------------------------------------------

	@Test
	public void testProcessEmitsTheReferenceOfTheCurrentMedia() {
		LoomMedia media = mediaLoader.load(videoA);

		NodeResult result = rootNode().process(media, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		// One declared output port, matching the descriptor: 'media : media/* ONE'. The former
		// 'source' and 'state' outputs were scan bookkeeping rather than pipeline data and were
		// never declared anywhere a graph could wire them.
		assertThat(result.get(FilesystemSourceNode.OUT_MEDIA)).isEqualTo(videoA.toString());
		assertThat(result.getOutputs()).containsOnlyKeys(FilesystemSourceNode.OUT_MEDIA.id());
	}

	@Test
	public void testDiffStateIsReadableWithoutBeingAnOutput() {
		FilesystemSourceNode node = rootNode();
		node.stream().count().blockingGet();

		assertThat(node.lastState(videoA.toString())).isEqualTo(FileState.NEW);
	}

	@Test
	public void testDiffStateIsUnknownForMediaThisRunDidNotEnumerate() {
		// The lastStates map is per-JVM; a node task landing on a different worker than the source
		// task cannot know the diff state.
		assertThat(rootNode().lastState(videoA.toString())).isEqualTo(FileState.UNKNOWN);
	}

	// --- Validation & factory ---------------------------------------------

	@Test
	public void testNodeWithoutAnySelectionIsRejected() {
		assertThatThrownBy(() -> new FilesystemSourceNode("fs-source", mediaLoader, null, List.of(), null, indexDir))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("root path or at least one path glob");
	}

	@Test
	public void testNodeWithoutMediaLoaderIsRejected() {
		assertThatThrownBy(() -> new FilesystemSourceNode("fs-source", null, root, List.of(), null, indexDir))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("media loader");
	}

	@Test
	public void testRootNodeWithoutIndexDirIsRejected() {
		assertThatThrownBy(() -> new FilesystemSourceNode("fs-source", mediaLoader, root, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("index base directory");
	}

	@Test
	public void testCreateFallsBackToConfiguredDefaultPath() {
		FilesystemSourceNodeOptions defaults = new FilesystemSourceNodeOptions().setPath(root.toString());

		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, null, List.of(), List.of(),
			defaults, indexDir);

		assertThat(node.root()).isEqualTo(root);
		assertThat(node.stream().count().blockingGet()).isEqualTo(2);
	}

	@Test
	public void testCreateFallsBackToConfiguredDefaultGlobs() {
		FilesystemSourceNodeOptions defaults = new FilesystemSourceNodeOptions()
			.setPathGlobs(List.of(root + "/a.mp4"));

		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, null, List.of(), List.of(),
			defaults, indexDir);

		assertThat(node.pathGlobs()).containsExactly(root + "/a.mp4");
		assertThat(node.stream().count().blockingGet()).isEqualTo(1);
	}

	@Test
	public void testCreateUsesDefinitionEmitStates() {
		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, root.toString(), List.of(),
			List.of("DELETED"), null, indexDir);

		assertThat(node.emitStates()).containsExactly(FileState.DELETED);
	}

	@Test
	public void testDefinitionSelectionOverridesConfiguredDefaults() {
		FilesystemSourceNodeOptions defaults = new FilesystemSourceNodeOptions()
			.setPath(root.resolve("ignored").toString());

		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, root.toString(),
			List.of(), List.of(), defaults, indexDir);

		assertThat(node.root()).isEqualTo(root);
	}

	@Test
	public void testCreateWithNoSelectionAnywhereIsRejected() {
		assertThatThrownBy(() -> FilesystemSourceNode.create("fs-source", mediaLoader, null, List.of(), List.of(),
			new FilesystemSourceNodeOptions(), indexDir))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
