package io.metaloom.cortex.node.source.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.cloud.CloudLoomMedia;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.FakeCloudFileStore;
import io.metaloom.fs.FileState;

public class CloudSourceNodeTest {

	@TempDir
	Path indexDir;

	@TempDir
	Path cacheDir;

	private FakeCloudFileStore store;
	private CloudMediaMaterializer materializer;

	@BeforeEach
	public void setup() {
		store = new FakeCloudFileStore(CloudProviderId.GDRIVE);
		materializer = new CloudMediaMaterializer(store, cacheDir, 0, 0);
	}

	private CloudSourceNode node(CloudSelection selection) {
		return new CloudSourceNode("gdrive-source",
			new CloudDifferentialScanner(store, new CloudFileIndexStore(), indexDir, 0), materializer, selection);
	}

	private CloudSelection selection(String folderId) {
		return selection(folderId, CloudSelection.DEFAULT_EMIT_STATES, true, 0);
	}

	private CloudSelection selection(String folderId, Set<FileState> states, boolean recursive, int maxDepth) {
		return new CloudSelection(CloudProviderId.GDRIVE, "d", folderId, recursive, maxDepth,
			Set.of(), Set.of(), states, false, false, false);
	}

	private List<String> references(CloudSourceNode node) {
		return node.stream().map(LoomMedia::reference).toList().blockingGet();
	}

	@Test
	public void testNodeIsMarkedAsASource() {
		assertThat(node(selection(null)).isSource()).isTrue();
	}

	@Test
	public void testInitialScanEmitsEverythingAsNew() {
		store.putFile("d", null, "a.mp4", "1");
		store.putFile("d", null, "b.mp4", "2");

		assertThat(references(node(selection(null)))).hasSize(2);
	}

	@Test
	public void testUnchangedRerunEmitsNothing() {
		store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));

		assertThat(references(node)).hasSize(1);
		assertThat(references(node)).isEmpty();
	}

	@Test
	public void testDetectsAnAddedFile() {
		store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));
		references(node);

		String added = store.putFile("d", null, "b.mp4", "2");
		assertThat(references(node)).containsExactly("gdrive://d/" + added + "/b.mp4");
	}

	@Test
	public void testDetectsAModifiedFile() {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));
		references(node);

		store.update("d", file, "changed");
		assertThat(references(node)).hasSize(1);
		assertThat(node.lastState("gdrive://d/" + file + "/a.mp4")).isEqualTo(FileState.MODIFIED);
	}

	@Test
	public void testAnUnchangedTokenIsNotReportedEvenWhenBytesDiffer() {
		String file = store.putFile("d", null, "a.mp4", "one");
		CloudSourceNode node = node(selection(null));
		references(node);

		// Same length and same change token, different bytes: the token is the signal we trust, so
		// nothing is reported. (A size change would be caught independently - see the next test.)
		store.putKeepingToken("d", file, "two");
		assertThat(references(node)).isEmpty();
	}

	@Test
	public void testASizeChangeIsReportedEvenIfTheTokenDidNot() {
		String file = store.putFile("d", null, "a.mp4", "one");
		CloudSourceNode node = node(selection(null));
		references(node);

		store.putKeepingToken("d", file, "much longer content");
		assertThat(references(node)).hasSize(1);
		assertThat(node.lastState("gdrive://d/" + file + "/a.mp4")).isEqualTo(FileState.MODIFIED);
	}

	/**
	 * The capability S3 cannot offer: a cloud file id survives a rename, so this is a MOVED rather
	 * than a delete plus an add.
	 */
	@Test
	public void testDetectsARenameAsMoved() {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));
		references(node);

		store.rename("d", file, "renamed.mp4");
		assertThat(references(node)).hasSize(1);
		assertThat(node.lastState("gdrive://d/" + file + "/renamed.mp4")).isEqualTo(FileState.MOVED);
	}

	@Test
	public void testDetectsAMoveBetweenFoldersAsMoved() {
		String folderA = store.putFolder("d", null, "A");
		String folderB = store.putFolder("d", null, "B");
		String file = store.putFile("d", folderA, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));
		references(node);

		store.move("d", file, folderB);
		assertThat(references(node)).hasSize(1);
		assertThat(node.lastState("gdrive://d/" + file + "/a.mp4")).isEqualTo(FileState.MOVED);
	}

	@Test
	public void testMovingOutOfTheSubtreeIsADeletionNotAMove() {
		String watched = store.putFolder("d", null, "Watched");
		String elsewhere = store.putFolder("d", null, "Elsewhere");
		String file = store.putFile("d", watched, "a.mp4", "1");

		CloudSourceNode node = node(selection(watched, Set.of(FileState.NEW, FileState.MOVED, FileState.DELETED), true, 0));
		references(node);

		store.move("d", file, elsewhere);
		assertThat(references(node)).hasSize(1);
		// From the pipeline's point of view the file is gone, whatever the drive thinks.
		assertThat(node.lastState("gdrive://d/" + file + "/a.mp4")).isEqualTo(FileState.DELETED);
	}

	@Test
	public void testMovingIntoTheSubtreeIsNew() {
		String watched = store.putFolder("d", null, "Watched");
		String elsewhere = store.putFolder("d", null, "Elsewhere");
		String file = store.putFile("d", elsewhere, "a.mp4", "1");

		CloudSourceNode node = node(selection(watched));
		assertThat(references(node)).isEmpty();

		store.move("d", file, watched);
		assertThat(references(node)).hasSize(1);
		assertThat(node.lastState("gdrive://d/" + file + "/a.mp4")).isEqualTo(FileState.NEW);
	}

	@Test
	public void testDeletedIsOnlyEmittedWhenRequested() {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));
		references(node);

		store.remove("d", file);
		assertThat(references(node)).isEmpty();
	}

	@Test
	public void testDeletedIsEmittedWhenAsked() {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null, Set.of(FileState.NEW, FileState.DELETED), true, 0));
		references(node);

		store.remove("d", file);
		assertThat(references(node)).containsExactly("gdrive://d/" + file + "/a.mp4");
	}

	@Test
	public void testStreamIsColdAndDoesNoWorkBeforeSubscription() {
		store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));

		var stream = node.stream();
		String late = store.putFile("d", null, "late.mp4", "2");

		// Built before the second file existed, subscribed after: a cold stream must still see it.
		assertThat(stream.map(LoomMedia::reference).toList().blockingGet())
			.contains("gdrive://d/" + late + "/late.mp4");
	}

	@Test
	public void testEnumerationNeverDownloadsBytes() {
		store.putFile("d", null, "a.mp4", "1");
		store.putFile("d", null, "b.mp4", "2");

		references(node(selection(null)));

		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testEmittedMediaAreLazyCloudHandles() {
		store.putFile("d", null, "a.mp4", "1");
		List<LoomMedia> media = node(selection(null)).stream().toList().blockingGet();

		assertThat(media.get(0)).isInstanceOf(CloudLoomMedia.class);
		assertThat(((CloudLoomMedia) media.get(0)).isMaterialized()).isFalse();
	}

	@Test
	public void testSuffixFilterRejectsOtherFiles() {
		store.putFile("d", null, "a.mp4", "1");
		store.putFile("d", null, "notes.txt", "2");

		CloudSelection selection = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of("mp4"), Set.of(), CloudSelection.DEFAULT_EMIT_STATES, false, false, false);

		assertThat(references(node(selection))).hasSize(1);
	}

	@Test
	public void testMimeTypeFilterRejectsOtherFiles() {
		store.putFile("d", null, "a.mp4", "1");
		store.putFile("d", null, "shot.jpg", "2");

		CloudSelection selection = new CloudSelection(CloudProviderId.GDRIVE, "d", null, true, 0,
			Set.of(), Set.of("video/"), CloudSelection.DEFAULT_EMIT_STATES, false, false, false);

		assertThat(references(node(selection))).hasSize(1);
	}

	@Test
	public void testNonRecursiveIgnoresSubfolders() {
		String folder = store.putFolder("d", null, "Nested");
		store.putFile("d", null, "top.mp4", "1");
		store.putFile("d", folder, "deep.mp4", "2");

		assertThat(references(node(selection(null, CloudSelection.DEFAULT_EMIT_STATES, false, 0)))).hasSize(1);
	}

	@Test
	public void testMaxDepthIsHonoured() {
		String level1 = store.putFolder("d", null, "L1");
		String level2 = store.putFolder("d", level1, "L2");
		store.putFile("d", level1, "shallow.mp4", "1");
		store.putFile("d", level2, "deep.mp4", "2");

		// maxDepth 1: descend into L1 but not into L2.
		assertThat(references(node(selection(null, CloudSelection.DEFAULT_EMIT_STATES, true, 1))))
			.hasSize(1);
	}

	@Test
	public void testTrashedFilesAreExcludedByDefault() {
		String file = store.putFile("d", null, "a.mp4", "1");
		store.trash("d", file);

		assertThat(references(node(selection(null)))).isEmpty();
	}

	@Test
	public void testFoldersAreNeverEmittedAsMedia() {
		store.putFolder("d", null, "Videos");

		assertThat(references(node(selection(null)))).isEmpty();
	}

	@Test
	public void testProcessEmitsTheReferenceNotTheAbsolutePath() {
		String file = store.putFile("d", null, "a.mp4", "1");
		CloudSourceNode node = node(selection(null));
		LoomMedia media = node.stream().toList().blockingGet().get(0);

		NodeResult result = node.process(media, null);

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		assertThat(result.getOutputs()).containsOnlyKeys(CloudSourceNode.OUT_MEDIA.id());
		assertThat(result.get(CloudSourceNode.OUT_MEDIA)).isEqualTo("gdrive://d/" + file + "/a.mp4");
		// Asking for the reference must not have materialized anything.
		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testLastStateIsUnknownForAReferenceThisRunDidNotEnumerate() {
		assertThat(node(selection(null)).lastState("gdrive://d/never/x.mp4")).isEqualTo(FileState.UNKNOWN);
	}

	@Test
	public void testNodeRequiresItsCollaborators() {
		CloudSelection selection = selection(null);
		CloudDifferentialScanner scanner =
			new CloudDifferentialScanner(store, new CloudFileIndexStore(), indexDir, 0);

		assertThatThrownBy(() -> new CloudSourceNode("x", null, materializer, selection))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scanner");
		assertThatThrownBy(() -> new CloudSourceNode("x", scanner, null, selection))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("materializer");
		assertThatThrownBy(() -> new CloudSourceNode("x", scanner, materializer, null))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection");
	}

	@Test
	public void testTheNodeNamesItsProvider() {
		assertThat(node(selection(null)).name()).isEqualTo("Google Drive Source");
	}

	@Test
	public void testCreateFallsBackToConfiguredDefaults() throws IOException {
		GDriveSourceNodeOptions defaults = new GDriveSourceNodeOptions();
		defaults.setFolderId("configured-folder").setSuffixes("mp4").setRecursive(false).setMaxDepth(3);

		CloudSourceNode node = CloudSourceNode.create("n1",
			new CloudDifferentialScanner(store, new CloudFileIndexStore(), indexDir, 0), materializer,
			CloudProviderId.GDRIVE, "d", null, null, null, null, null, List.of(), null, null, false, defaults);

		assertThat(node.selection().folderId()).isEqualTo("configured-folder");
		assertThat(node.selection().suffixes()).containsExactly("mp4");
		assertThat(node.selection().recursive()).isFalse();
		assertThat(node.selection().maxDepth()).isEqualTo(3);
	}

	@Test
	public void testDefinitionOverridesConfiguredDefaults() {
		GDriveSourceNodeOptions defaults = new GDriveSourceNodeOptions();
		defaults.setFolderId("configured-folder").setRecursive(true).setUseDelta(true);

		CloudSourceNode node = CloudSourceNode.create("n1",
			new CloudDifferentialScanner(store, new CloudFileIndexStore(), indexDir, 0), materializer,
			CloudProviderId.GDRIVE, "d", "definition-folder", false, 2, "jpg", "image/",
			List.of("NEW"), false, true, false, defaults);

		assertThat(node.selection().folderId()).isEqualTo("definition-folder");
		assertThat(node.selection().recursive()).isFalse();
		assertThat(node.selection().maxDepth()).isEqualTo(2);
		assertThat(node.selection().suffixes()).containsExactly("jpg");
		assertThat(node.selection().mimeTypes()).containsExactly("image/");
		assertThat(node.selection().emitStates()).containsExactly(FileState.NEW);
		assertThat(node.selection().useDelta()).isFalse();
		assertThat(node.selection().includeTrashed()).isTrue();
	}
}
