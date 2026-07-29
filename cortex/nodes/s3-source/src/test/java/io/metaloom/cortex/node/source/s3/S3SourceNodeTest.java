package io.metaloom.cortex.node.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.s3.FakeS3ObjectStore;
import io.metaloom.cortex.s3.S3LoomMedia;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.fs.FileState;

/**
 * Mirrors {@code FilesystemSourceNodeTest} for the object-storage source: first scan sees
 * everything, an unchanged re-run emits nothing, and only the configured states flow downstream.
 */
public class S3SourceNodeTest {

	private static final String BUCKET = "media";

	@TempDir
	Path indexDir;

	@TempDir
	Path cacheDir;

	private FakeS3ObjectStore store;
	private S3MediaMaterializer materializer;

	@BeforeEach
	public void setup() {
		store = new FakeS3ObjectStore()
			.put(BUCKET, "2026/a.mp4", "a")
			.put(BUCKET, "2026/b.mp4", "b");
		materializer = new S3MediaMaterializer(store, cacheDir, 0, 0);
	}

	private S3DifferentialScanner scanner() {
		return new S3DifferentialScanner(store, new S3ObjectIndexStore(), null, indexDir,
			"http://minio:9000", S3ClientDefaults.RECONCILE_MS);
	}

	private S3SourceNode node() {
		return node(null);
	}

	private S3SourceNode node(Set<FileState> emitStates) {
		return new S3SourceNode("s3-source", scanner(), materializer,
			new S3Selection(BUCKET, "2026/", Set.of(), emitStates, false, false));
	}

	private List<String> references(S3SourceNode node) {
		return node.stream().map(LoomMedia::reference).toList().blockingGet();
	}

	@Test
	public void testInitialScanEmitsAllObjectsAsNew() {
		assertThat(references(node()))
			.containsExactlyInAnyOrder("s3://media/2026/a.mp4", "s3://media/2026/b.mp4");
	}

	@Test
	public void testUnchangedRerunEmitsNothing() {
		S3SourceNode node = node();
		assertThat(references(node)).hasSize(2);

		assertThat(node.stream().count().blockingGet()).isZero();
	}

	@Test
	public void testDetectsAddedObject() {
		S3SourceNode node = node();
		references(node);

		store.put(BUCKET, "2026/c.mp4", "c");

		assertThat(references(node)).containsExactly("s3://media/2026/c.mp4");
	}

	@Test
	public void testDetectsModifiedObject() {
		S3SourceNode node = node();
		references(node);

		store.put(BUCKET, "2026/a.mp4", "a-changed");

		assertThat(references(node)).containsExactly("s3://media/2026/a.mp4");
	}

	@Test
	public void testUnchangedEtagIsNotReportedEvenWhenBytesDiffer() {
		// The etag is the change token. A store that reports an unchanged etag is claiming the
		// object is unchanged, and the scanner takes it at its word - which is the whole point of
		// not downloading anything to detect changes.
		S3SourceNode node = node();
		references(node);

		store.putKeepingEtag(BUCKET, "2026/a.mp4", "b");

		assertThat(node.stream().count().blockingGet()).isZero();
	}

	@Test
	public void testDeletedObjectsAreOnlyEmittedWhenRequested() {
		S3SourceNode defaultNode = node();
		references(defaultNode);
		store.remove(BUCKET, "2026/b.mp4");

		// DELETED is not in the default emit set.
		assertThat(defaultNode.stream().count().blockingGet()).isZero();
	}

	@Test
	public void testEmitStatesCanSelectRemovedObjectsOnly() {
		S3SourceNode node = node(Set.of(FileState.DELETED));

		// Nothing is deleted yet, so the first scan emits nothing even though it indexes both.
		assertThat(node.stream().count().blockingGet()).isZero();

		store.remove(BUCKET, "2026/b.mp4");

		assertThat(references(node)).containsExactly("s3://media/2026/b.mp4");
	}

	@Test
	public void testEmitStatesIncludingPresentKeepsEmittingEverything() {
		S3SourceNode node = node(Set.of(FileState.NEW, FileState.MODIFIED, FileState.PRESENT));
		assertThat(references(node)).hasSize(2);

		assertThat(references(node)).hasSize(2);
	}

	@Test
	public void testStreamIsColdAndDoesNoWorkBeforeSubscription() {
		S3SourceNode node = node();
		var stream = node.stream();
		// Objects added after stream() but before subscription must still be seen.
		store.put(BUCKET, "2026/late.mp4", "late");

		assertThat(stream.map(LoomMedia::reference).toList().blockingGet())
			.contains("s3://media/2026/late.mp4");
	}

	@Test
	public void testEnumerationNeverDownloadsBytes() {
		// The whole point of emitting references: a bucket scan costs metadata, not bandwidth.
		references(node());

		assertThat(store.downloadCalls).hasValue(0);
	}

	@Test
	public void testEmittedMediaAreLazyS3Handles() {
		LoomMedia media = node().stream().blockingFirst();

		assertThat(media).isInstanceOf(S3LoomMedia.class);
		assertThat(((S3LoomMedia) media).isMaterialized()).isFalse();
	}

	@Test
	public void testPrefixLimitsTheSelection() {
		store.put(BUCKET, "2025/old.mp4", "old");

		assertThat(references(node())).noneMatch(ref -> ref.contains("2025/"));
	}

	@Test
	public void testSuffixFilterRejectsOtherObjects() {
		store.put(BUCKET, "2026/notes.txt", "text");
		S3SourceNode node = new S3SourceNode("s3-source", scanner(), materializer,
			new S3Selection(BUCKET, "2026/", Set.of("mp4"), null, false, false));

		assertThat(references(node)).noneMatch(ref -> ref.endsWith(".txt"));
	}

	@Test
	public void testPaginationIsFollowed() {
		store.pageSize(2);
		for (int i = 0; i < 7; i++) {
			store.put(BUCKET, "2026/bulk-" + i + ".mp4", "x" + i);
		}

		// 2 originals + 7 bulk objects, gathered across several pages.
		assertThat(references(node())).hasSize(9);
		assertThat(store.listCalls.get()).isGreaterThan(1);
	}

	@Test
	public void testProcessEmitsTheObjectReference() {
		S3SourceNode node = node();
		LoomMedia media = node.stream().blockingFirst();

		NodeResult result = node.process(media, NodeInputs.empty());

		assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);
		// One declared output port, matching the descriptor: 'media : media/* ONE'. It is named
		// 'media' exactly as filesystem-source and asset-source name theirs, so a downstream node
		// stays wired when the source is swapped. The value is the s3:// reference, not a local
		// path - the object may not be materialized anywhere yet.
		assertThat(result.get(S3SourceNode.OUT_MEDIA)).startsWith("s3://media/2026/");
		// Bucket, key, provenance and diff state were scan bookkeeping, never declared ports, so a
		// graph could not wire them; they are read off the node instead.
		assertThat(result.getOutputs()).containsOnlyKeys(S3SourceNode.OUT_MEDIA.id());
	}

	@Test
	public void testDiffStateIsReadableWithoutBeingAnOutput() {
		S3SourceNode node = node();
		LoomMedia media = node.stream().blockingFirst();

		assertThat(node.lastState(media.reference())).isEqualTo(FileState.NEW);
	}

	@Test
	public void testDiffStateIsUnknownForMediaThatThisRunDidNotEnumerate() {
		// The lastStates map is per-JVM; a node task landing on a different worker than the
		// source task cannot know the diff state. Same limitation as filesystem-source.
		S3SourceNode node = node();
		LoomMedia media = node.stream().blockingFirst();
		S3SourceNode otherWorker = node();

		assertThat(otherWorker.lastState(media.reference())).isEqualTo(FileState.UNKNOWN);
	}

	@Test
	public void testNodeRequiresItsCollaborators() {
		S3Selection selection = new S3Selection(BUCKET, null, Set.of(), null, false, false);
		assertThatThrownBy(() -> new S3SourceNode("s3-source", null, materializer, selection))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scanner");
		assertThatThrownBy(() -> new S3SourceNode("s3-source", scanner(), null, selection))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("materializer");
		assertThatThrownBy(() -> new S3SourceNode("s3-source", scanner(), materializer, null))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection");
	}

	@Test
	public void testCreateRequiresABucketFromSomewhere() {
		assertThatThrownBy(() -> S3SourceNode.create("s3-source", scanner(), materializer,
			null, null, null, List.of(), null, null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'bucket' option");
	}

	@Test
	public void testCreateFallsBackToConfiguredDefaults() {
		S3SourceNodeOptions defaults = new S3SourceNodeOptions()
			.setBucket("configured").setPrefix("p/").setSuffixes("mp4");

		S3SourceNode node = S3SourceNode.create("s3-source", scanner(), materializer,
			null, null, null, List.of(), null, null, defaults);

		assertThat(node.selection().bucket()).isEqualTo("configured");
		assertThat(node.selection().prefix()).isEqualTo("p/");
		assertThat(node.selection().suffixes()).containsExactly("mp4");
	}

	@Test
	public void testDefinitionOverridesConfiguredDefaults() {
		S3SourceNodeOptions defaults = new S3SourceNodeOptions().setBucket("configured").setPrefix("p/");

		S3SourceNode node = S3SourceNode.create("s3-source", scanner(), materializer,
			"from-definition", "q/", "mkv", List.of("PRESENT"), true, true, defaults);

		assertThat(node.selection().bucket()).isEqualTo("from-definition");
		assertThat(node.selection().prefix()).isEqualTo("q/");
		assertThat(node.selection().emitStates()).containsExactly(FileState.PRESENT);
		assertThat(node.selection().startAfter()).isTrue();
		assertThat(node.selection().useEvents()).isTrue();
	}

	/** Keeps the reconcile interval out of the way for tests that are not about it. */
	private static final class S3ClientDefaults {
		private static final long RECONCILE_MS = 6L * 60 * 60 * 1000;
	}
}
