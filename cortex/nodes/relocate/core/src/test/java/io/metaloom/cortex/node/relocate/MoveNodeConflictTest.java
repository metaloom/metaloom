package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.cortexOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.folderNode;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.folderOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.mediaWith;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What happens when the destination is taken.
 *
 * <p>
 * The invariant every case shares: <b>the occupant is never overwritten</b>. A name collision in a trash or archive folder is very often a genuinely
 * different asset, and overwriting it would destroy the only copy.
 * </p>
 */
class MoveNodeConflictTest {

	@TempDir
	File tempDir;

	private Path occupy(File folder, String name, String content) throws Exception {
		assertTrue(folder.exists() || folder.mkdirs());
		return Files.writeString(folder.toPath().resolve(name), content);
	}

	@Test
	void testSuffixLandsNextToTheOccupant() throws Exception {
		File trash = new File(tempDir, "trash");
		Path occupant = occupy(trash, "clip.mp4", "existing");
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "incoming");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals("existing", Files.readString(occupant, UTF_8), "the occupant must be untouched");
		assertEquals("incoming", Files.readString(trash.toPath().resolve("clip_1.mp4"), UTF_8));
		assertEquals(trash.toPath().resolve("clip_1.mp4").toAbsolutePath().toString(), result.get(MoveNode.OUT_PATH),
			"the emitted path must be where the bytes actually landed, not where they were aimed");
	}

	@Test
	void testSuffixNumbersUpwardsAcrossRuns() throws Exception {
		File trash = new File(tempDir, "trash");
		occupy(trash, "clip.mp4", "existing");
		occupy(trash, "clip_1.mp4", "existing-1");
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "incoming");

		folderNode(null, cortexOptions(tempDir), folderOptions(trash)).process(NodeContext.create(media));

		assertEquals("incoming", Files.readString(trash.toPath().resolve("clip_2.mp4"), UTF_8));
	}

	@Test
	void testSkipLeavesBothFilesAlone() throws Exception {
		File trash = new File(tempDir, "trash");
		Path occupant = occupy(trash, "clip.mp4", "existing");
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "incoming");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash).setOnConflict("SKIP"))
			.process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertEquals("existing", Files.readString(occupant, UTF_8), "the occupant must be untouched");
		assertEquals("incoming", Files.readString(Path.of(media.absolutePath()), UTF_8), "the source must be untouched");
	}

	@Test
	void testFailReportsFailedAndLeavesBothFilesAlone() throws Exception {
		File trash = new File(tempDir, "trash");
		Path occupant = occupy(trash, "clip.mp4", "existing");
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "incoming");

		NodeResult result = folderNode(null, cortexOptions(tempDir), folderOptions(trash).setOnConflict("FAIL"))
			.process(NodeContext.create(media));

		assertThat(result).isFailed();
		assertEquals("existing", Files.readString(occupant, UTF_8), "the occupant must be untouched");
		assertEquals("incoming", Files.readString(Path.of(media.absolutePath()), UTF_8), "the source must be untouched");
	}
}
