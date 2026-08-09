package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.cortexOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.folderNode;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.folderOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.mediaWith;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * The filesystem happy paths, offline ({@code LoomClient == null}).
 */
class MoveNodeTest {

	@TempDir
	File tempDir;

	@Test
	void testMovesTheFileIntoTheTargetFolder() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File trash = new File(tempDir, "trash");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals(true, result.get(MoveNode.OUT_MOVED));
		assertEquals("MOVED", result.get(MoveNode.OUT_FLAG));

		Path target = trash.toPath().resolve("clip.mp4");
		assertTrue(Files.exists(target), "the file should be in the trash folder");
		assertEquals("payload", Files.readString(target, UTF_8));
		assertFalse(Files.exists(Path.of(media.absolutePath())) && !media.absolutePath().equals(target.toString()),
			"the original path should no longer hold the file");
	}

	/**
	 * The media handle has to follow the bytes, or a node later in the same graph opens a path that no longer exists.
	 *
	 * <p>
	 * {@code StubLoomMedia.setPath} is a no-op, so the shared stub cannot show this; a recording subclass can, without changing behaviour every other
	 * node's tests depend on.
	 * </p>
	 */
	@Test
	void testTheMediaHandleFollowsTheBytes() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		Path[] recorded = new Path[1];
		StubLoomMedia recording = new StubLoomMedia(media.absolutePath(), false, true, false, false) {
			@Override
			public void setPath(Path path) {
				recorded[0] = path;
			}
		};
		recording.setSHA512(RelocateTestFixtures.HASH);
		File trash = new File(tempDir, "trash");

		folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(recording));

		assertEquals(trash.toPath().resolve("clip.mp4").toAbsolutePath(), recorded[0].toAbsolutePath(),
			"the media handle should be re-pointed at the new location");
	}

	/**
	 * The mirror of the above: after a copy the item is still where the rest of the graph expects it, so re-pointing the handle would make every later
	 * node read the archive instead of the working file.
	 */
	@Test
	void testTheMediaHandleIsNotMovedAfterACopy() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		Path[] recorded = new Path[1];
		StubLoomMedia recording = new StubLoomMedia(media.absolutePath(), false, true, false, false) {
			@Override
			public void setPath(Path path) {
				recorded[0] = path;
			}
		};
		recording.setSHA512(RelocateTestFixtures.HASH);
		File archive = new File(tempDir, "archive");

		MoveNodeOptions options = folderOptions(archive)
			.setCrossDevice("COPY")
			.setSourcePolicy("KEEP")
			.setVerify("SIZE");

		NodeResult result = RelocateTestFixtures.crossDeviceFolderNode(null, cortexOptions(tempDir), options)
			.process(NodeContext.create(recording));

		assertThat(result).isSuccess();
		assertEquals("COPIED", result.get(MoveNode.OUT_FLAG));
		assertEquals(null, recorded[0], "a copy must leave the handle pointing at the original");
	}

	@Test
	void testTheEmittedPathIsTheNewLocation() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File trash = new File(tempDir, "trash");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertEquals(trash.toPath().resolve("clip.mp4").toAbsolutePath().toString(), result.get(MoveNode.OUT_PATH));
	}

	/**
	 * A file already inside the target folder is an idempotent skip, so a second run over the same corpus writes nothing.
	 */
	@Test
	void testAFileAlreadyInTheTargetIsSkipped() throws Exception {
		File trash = new File(tempDir, "trash");
		assertTrue(trash.mkdirs());
		StubLoomMedia media = mediaWith(trash, "clip.mp4", "payload");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertEquals(false, result.get(MoveNode.OUT_MOVED));
		assertEquals("ALREADY_THERE", result.get(MoveNode.OUT_FLAG));
		assertTrue(Files.exists(trash.toPath().resolve("clip.mp4")), "the file should not have been touched");
	}

	/**
	 * 🔴 The containment regression. {@code /data/dups-old} is not inside {@code /data/dups}, so a file in the neighbouring folder must actually move.
	 * The previous implementation compared normalised paths with {@code String.startsWith} and reported it as already relocated.
	 */
	@Test
	void testASiblingFolderWithAMatchingPrefixIsNotTheTarget() throws Exception {
		File trash = new File(tempDir, "trash");
		File lookalike = new File(tempDir, "trash-old");
		assertTrue(lookalike.mkdirs());
		StubLoomMedia media = mediaWith(lookalike, "clip.mp4", "payload");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertTrue(Files.exists(trash.toPath().resolve("clip.mp4")), "the file should have moved out of the look-alike folder");
	}

	@Test
	void testDryRunTouchesNothing() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File trash = new File(tempDir, "trash");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash).setDryRun(true))
			.process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertEquals("DRY_RUN", result.get(MoveNode.OUT_FLAG));
		assertEquals(trash.toPath().resolve("clip.mp4").toAbsolutePath().toString(), result.get(MoveNode.OUT_PATH),
			"a dry run should still report where the file would have gone");
		assertFalse(Files.exists(trash.toPath().resolve("clip.mp4")), "nothing should have been written");
		assertTrue(Files.exists(Path.of(media.absolutePath())), "the source should be untouched");
	}

	/**
	 * A materialized remote item's path points at a download cache, not at the asset. Relocating that would move a cache entry and then tell Loom the
	 * asset had moved with it.
	 */
	@Test
	void testRemoteMediaIsLeftAlone() throws Exception {
		StubLoomMedia media = new StubLoomMedia(new File(tempDir, "cached.mp4").getAbsolutePath()) {
			@Override
			public String reference() {
				return "s3://a-bucket/ab/cd/ef/deadbeef";
			}
		};
		Files.writeString(Path.of(media.absolutePath()), "payload");
		File trash = new File(tempDir, "trash");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertFalse(Files.exists(trash.toPath()), "nothing should have been created");
		assertTrue(Files.exists(Path.of(media.absolutePath())), "the cached file should be untouched");
	}

	@Test
	void testTheMirrorLayoutPreservesThePathBelowTheSourceRoot() throws Exception {
		File corpus = new File(tempDir, "corpus/photos/2024");
		assertTrue(corpus.mkdirs());
		StubLoomMedia media = mediaWith(corpus, "clip.mp4", "payload");
		File trash = new File(tempDir, "trash");

		MoveNodeOptions options = folderOptions(trash)
			.setLayout(Layout.MIRROR)
			.setSourceRoot(new File(tempDir, "corpus").toPath());

		folderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(media));

		assertTrue(Files.exists(trash.toPath().resolve("photos/2024/clip.mp4")), "the path below the source root should be preserved");
	}

	/**
	 * With no source root there is nothing to be relative to. Mirroring the absolute path instead would recreate the whole filesystem tree under the
	 * target, so the layout falls back to flat.
	 */
	@Test
	void testMirrorWithoutASourceRootFallsBackToFlat() throws Exception {
		File corpus = new File(tempDir, "corpus/photos");
		assertTrue(corpus.mkdirs());
		StubLoomMedia media = mediaWith(corpus, "clip.mp4", "payload");
		File trash = new File(tempDir, "trash");

		folderNode(null, cortexOptions(tempDir), folderOptions(trash).setLayout(Layout.MIRROR)).process(NodeContext.create(media));

		assertTrue(Files.exists(trash.toPath().resolve("clip.mp4")), "the file should be directly in the target folder");
	}

	/**
	 * The content layout has to agree with Loom's own storage layout, which is three two-character segments and then the full hash - not the
	 * single four-character shard the worker's artifact caches use.
	 */
	@Test
	void testTheContentLayoutMatchesLoomsStorageLayout() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		folderNode(null, cortexOptions(tempDir), folderOptions(archive).setLayout(Layout.CONTENT)).process(NodeContext.create(media));

		String hex = RelocateTestFixtures.HASH.toString().toLowerCase();
		Path expected = archive.toPath().resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex.substring(4, 6)).resolve(hex);
		assertTrue(Files.exists(expected), "expected the content-addressed path " + expected);
	}
}
