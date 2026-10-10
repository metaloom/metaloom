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
 * The content-addressed layout, where an occupied destination means something different.
 *
 * <p>
 * 🔴 Under {@link Layout#CONTENT} the destination path <b>is</b> the SHA-512, so a file already sitting there holds the same bytes. Treating that as a
 * name collision files the incoming copy at {@code <sha512>_1} - a path that is not a content key, so Loom, which derives the location it reads from
 * the hash, never looks there. With {@code sourcePolicy: DELETE_AFTER_VERIFY} the original is unlinked as well, and the asset's bytes are then only at
 * a path nothing can find.
 * </p>
 */
class MoveNodeContentLayoutTest {

	@TempDir
	File tempDir;

	private Path contentPath(File root) {
		String hex = RelocateTestFixtures.HASH.toString().toLowerCase();
		return root.toPath().resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex.substring(4, 6)).resolve(hex);
	}

	private MoveNodeOptions contentOptions(File archive) {
		return folderOptions(archive).setLayout(Layout.CONTENT);
	}

	/**
	 * The same content arriving twice: the second run reports it is already there and writes nothing.
	 */
	@Test
	void testASecondCopyOfTheSameContentIsAlreadyThere() throws Exception {
		File archive = new File(tempDir, "archive");
		File corpus = new File(tempDir, "corpus");
		assertTrue(corpus.mkdirs());

		StubLoomMedia first = mediaWith(corpus, "clip.mp4", "payload");
		folderNode(null, cortexOptions(tempDir), contentOptions(archive)).process(NodeContext.create(first));
		Path stored = contentPath(archive);
		assertTrue(Files.exists(stored), "the first copy should be at the content path " + stored);

		// A second file with identical bytes, and therefore the same hash and the same content path.
		StubLoomMedia second = mediaWith(corpus, "clip-copy.mp4", "payload");
		NodeResult result = folderNode(null, cortexOptions(tempDir), contentOptions(archive)).process(NodeContext.create(second));

		assertThat(result).isSkipped();
		assertEquals(false, result.get(MoveNode.OUT_MOVED));
		assertEquals("ALREADY_THERE", result.get(MoveNode.OUT_FLAG));
		assertEquals(stored.toAbsolutePath().toString(), result.get(MoveNode.OUT_PATH),
			"the reported location must be where the bytes are, which is the content path");
		assertFalse(Files.exists(stored.resolveSibling(stored.getFileName() + "_1")), "no numbered duplicate may be written");
		try (var files = Files.list(stored.getParent())) {
			assertEquals(1, files.count(), "the content path must hold exactly one file");
		}
		assertTrue(Files.exists(Path.of(second.absolutePath())), "an already-stored source is left alone rather than deleted");
	}

	/**
	 * The dangerous combination: the duplicate must not be deleted on the strength of a copy that landed at a numbered name.
	 */
	@Test
	void testADuplicateIsNotDeletedWhenTheContentIsAlreadyStored() throws Exception {
		File archive = new File(tempDir, "archive");
		File corpus = new File(tempDir, "corpus");
		assertTrue(corpus.mkdirs());

		StubLoomMedia first = mediaWith(corpus, "clip.mp4", "payload");
		MoveNodeOptions options = contentOptions(archive).setSourcePolicy("DELETE_AFTER_VERIFY");
		folderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(first));

		StubLoomMedia second = mediaWith(corpus, "clip-copy.mp4", "payload");
		NodeResult result = folderNode(null, cortexOptions(tempDir), contentOptions(archive).setSourcePolicy("DELETE_AFTER_VERIFY"))
			.process(NodeContext.create(second));

		assertThat(result).isSkipped();
		assertEquals("payload", Files.readString(contentPath(archive), UTF_8));
		assertTrue(Files.exists(Path.of(second.absolutePath())),
			"the duplicate source must survive: nothing new was written, so there is nothing to have replaced it");
	}

	/**
	 * A different length at the content path is not this content, so the conflict policy still applies. It takes a hash collision to get here, and
	 * silently answering "already there" would then lose the incoming file.
	 */
	@Test
	void testADifferentFileAtTheContentPathIsStillAConflict() throws Exception {
		File archive = new File(tempDir, "archive");
		Path occupied = contentPath(archive);
		Files.createDirectories(occupied.getParent());
		Files.writeString(occupied, "something else entirely");

		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");

		NodeResult result = folderNode(null, cortexOptions(tempDir), contentOptions(archive)).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals("something else entirely", Files.readString(occupied, UTF_8), "the occupant must be untouched");
		assertEquals("payload", Files.readString(occupied.resolveSibling(occupied.getFileName() + "_1"), UTF_8));
	}

	/**
	 * The guard is for the content layout only: elsewhere a taken destination really is two files wanting one name, and the occupant must not be read as
	 * "this file is already filed".
	 */
	@Test
	void testAFlatLayoutStillTreatsAnOccupiedDestinationAsACollision() throws Exception {
		File trash = new File(tempDir, "trash");
		assertTrue(trash.mkdirs());
		Files.writeString(trash.toPath().resolve("clip.mp4"), "payload");
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals("MOVED", result.get(MoveNode.OUT_FLAG));
		assertTrue(Files.exists(trash.toPath().resolve("clip_1.mp4")), "same name, same size, different file: it gets its own name");
	}
}
