package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.cortexOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.folderNode;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.folderOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.mediaWith;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.fs.LocalMover;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClient;

/**
 * What the node does when the filesystem says no.
 *
 * <p>
 * Every case asserts the same two things: the result is FAILED - not a green run that moved nothing - and the <b>source file is intact</b>. A mover may
 * refuse to work; it may not lose the file while refusing.
 * </p>
 */
class MoveNodeFailureTest {

	@TempDir
	File tempDir;

	/**
	 * The destination root is a regular file, so the target directory cannot be created. This is the shape every "cannot write there" failure takes -
	 * permission denied, a full disk, a read-only mount - and the one that can be provoked deterministically and without root.
	 */
	@Test
	void testAnUnwritableDestinationFailsAndKeepsTheSource() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File blocked = new File(tempDir, "trash");
		Files.writeString(blocked.toPath(), "I am a file, not a folder");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(blocked)).process(NodeContext.create(media));

		assertThat(result).isFailed();
		assertEquals("FAILED", result.get(MoveNode.OUT_FLAG));
		assertEquals(false, result.get(MoveNode.OUT_MOVED));
		assertEquals("payload", Files.readString(Path.of(media.absolutePath()), UTF_8), "the source must be intact");
		assertEquals("I am a file, not a folder", Files.readString(blocked.toPath(), UTF_8), "and so must whatever was in the way");
	}

	/**
	 * A failure whose exception carries no message must still say something. A ledger row reading {@code null} tells an operator that the file mover
	 * failed and nothing else.
	 */
	@Test
	void testAFailureWithoutAMessageStillNamesItsCause() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");

		Map<MoveTarget, Provider<MoveDestination>> destinations = Map.of(MoveTarget.FOLDER, () -> new MoveDestination() {
			@Override
			public MoveTarget target() {
				return MoveTarget.FOLDER;
			}

			@Override
			public MovePlan plan(LoomClient client, LoomMedia item, MoveNodeOptions options) {
				throw new NullPointerException();
			}
		});
		MoveNode node = new MoveNode(null, cortexOptions(tempDir), folderOptions(new File(tempDir, "trash")), destinations,
			new LoomLocationWriter(), S3Support.inactive(), new LocalMover());

		NodeResult result = node.process(NodeContext.create(media));

		assertThat(result).isFailed();
		assertNotNull(result.getMessage(), "a failure must carry a reason");
		assertTrue(result.getMessage().contains("NullPointerException"), "expected the exception to be named, got: " + result.getMessage());
		assertTrue(Files.exists(Path.of(media.absolutePath())), "the source must be intact");
	}

	/**
	 * A source that vanished between the scan and the move is a failure, not a move of nothing - and it must not create the destination folder on the
	 * way out.
	 */
	@Test
	void testAMissingSourceFailsAndWritesNothing() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		Files.delete(Path.of(media.absolutePath()));
		File trash = new File(tempDir, "trash");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isFailed().hasMessageContaining("not found");
		assertFalse(Files.exists(trash.toPath()), "nothing should have been created");
	}

	/**
	 * The MIRROR layout builds the destination from the path below the source root. A file that is not below it has no relative path, and inventing one
	 * from the absolute path would recreate the filesystem tree under the target - or, with {@code ../} segments, land outside it. It falls back to flat,
	 * so the destination stays inside the target root whatever the source path looks like.
	 */
	@Test
	void testTheMirrorLayoutCannotPlaceAFileOutsideTheTargetRoot() throws Exception {
		File corpus = new File(tempDir, "corpus");
		File outside = new File(tempDir, "outside");
		assertTrue(corpus.mkdirs() && outside.mkdirs());
		File trash = new File(tempDir, "trash");

		// A path that reads as if it were inside the corpus but normalises to a sibling of it.
		StubLoomMedia media = mediaWith(outside, "clip.mp4", "payload");
		StubLoomMedia traversing = new StubLoomMedia(corpus.getAbsolutePath() + "/../outside/clip.mp4", false, true, false, false);
		traversing.setSHA512(RelocateTestFixtures.HASH);

		MoveNodeOptions options = folderOptions(trash)
			.setLayout(Layout.MIRROR)
			.setSourceRoot(corpus.toPath());

		NodeResult result = folderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(traversing));

		assertThat(result).isSuccess();
		assertTrue(Files.exists(trash.toPath().resolve("clip.mp4")), "the file should have landed directly in the target root");
		try (var walk = Files.walk(tempDir.toPath())) {
			assertFalse(walk.anyMatch(p -> p.getFileName().toString().equals("clip.mp4") && !p.startsWith(trash.toPath())),
				"no copy of the file may exist outside the target root");
		}
		assertEquals("payload", Files.readString(trash.toPath().resolve("clip.mp4"), UTF_8));
		assertFalse(Files.exists(Path.of(media.absolutePath())), "the file was renamed out of its original folder");
	}
}
