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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;

public class FilesystemSourceNodeTest {

	@TempDir
	Path root;

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
		return new FilesystemSourceNode("fs-source", mediaLoader, root, List.of());
	}

	@Test
	public void testNodeIsMarkedAsSource() {
		assertThat(rootNode().isSource()).isTrue();
	}

	@Test
	public void testStreamWalksConfiguredRoot() {
		List<Path> emitted = rootNode().stream()
			.map(LoomMedia::path)
			.toList()
			.blockingGet();

		assertThat(emitted).containsExactlyInAnyOrder(videoA, videoB);
	}

	@Test
	public void testStreamUsesGlobsInPreferenceToRoot() {
		FilesystemSourceNode node = new FilesystemSourceNode("fs-source", mediaLoader, root,
			List.of(root + "/a.mp4"));

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

	@Test
	public void testStreamRescansOnEachSubscription() throws IOException {
		FilesystemSourceNode node = rootNode();

		assertThat(node.stream().count().blockingGet()).isEqualTo(2);

		Files.writeString(root.resolve("c.mp4"), "c");

		// A node registered once in a pipeline must pick up new files on a re-run.
		assertThat(node.stream().count().blockingGet()).isEqualTo(3);
	}

	@Test
	public void testStreamOnEmptyDirectoryCompletesWithoutEmitting() throws IOException {
		Path empty = Files.createDirectory(root.resolve("empty"));
		FilesystemSourceNode node = new FilesystemSourceNode("fs-source", mediaLoader, empty, List.of());

		assertThat(node.stream().count().blockingGet()).isZero();
	}

	@Test
	public void testStreamOnMissingRootCompletesWithoutEmitting() {
		FilesystemSourceNode node = new FilesystemSourceNode("fs-source", mediaLoader,
			root.resolve("does-not-exist"), List.of());

		assertThat(node.stream().count().blockingGet()).isZero();
	}

	@Test
	public void testProcessReportsThePathOfTheCurrentMedia() {
		LoomMedia media = mediaLoader.load(videoA);

		NodeResult result = rootNode().process(media, Map.of());

		assertThat(result.getState()).isEqualTo(NodeState.COMPLETED);
		assertThat((Object) result.getOutput("path")).isEqualTo(videoA.toString());
		assertThat((Object) result.getOutput("source")).isEqualTo("filesystem");
	}

	@Test
	public void testNodeWithoutAnySelectionIsRejected() {
		assertThatThrownBy(() -> new FilesystemSourceNode("fs-source", mediaLoader, null, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("root path or at least one path glob");
	}

	@Test
	public void testNodeWithoutMediaLoaderIsRejected() {
		assertThatThrownBy(() -> new FilesystemSourceNode("fs-source", null, root, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("media loader");
	}

	@Test
	public void testCreateFallsBackToConfiguredDefaultPath() {
		FilesystemSourceNodeOptions defaults = new FilesystemSourceNodeOptions().setPath(root.toString());

		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, null, List.of(), defaults);

		assertThat(node.root()).isEqualTo(root);
		assertThat(node.stream().count().blockingGet()).isEqualTo(2);
	}

	@Test
	public void testCreateFallsBackToConfiguredDefaultGlobs() {
		FilesystemSourceNodeOptions defaults = new FilesystemSourceNodeOptions()
			.setPathGlobs(List.of(root + "/a.mp4"));

		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, null, List.of(), defaults);

		assertThat(node.pathGlobs()).containsExactly(root + "/a.mp4");
		assertThat(node.stream().count().blockingGet()).isEqualTo(1);
	}

	@Test
	public void testDefinitionSelectionOverridesConfiguredDefaults() {
		FilesystemSourceNodeOptions defaults = new FilesystemSourceNodeOptions()
			.setPath(root.resolve("ignored").toString());

		FilesystemSourceNode node = FilesystemSourceNode.create("fs-source", mediaLoader, root.toString(),
			List.of(), defaults);

		assertThat(node.root()).isEqualTo(root);
	}

	@Test
	public void testCreateWithNoSelectionAnywhereIsRejected() {
		assertThatThrownBy(() -> FilesystemSourceNode.create("fs-source", mediaLoader, null, List.of(),
			new FilesystemSourceNodeOptions()))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
