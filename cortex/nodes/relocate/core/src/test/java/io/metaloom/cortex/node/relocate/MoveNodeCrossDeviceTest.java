package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.cortexOptions;
import static io.metaloom.cortex.node.relocate.RelocateTestFixtures.crossDeviceFolderNode;
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
 * The three cross-device policies, with a stubbed filesystem-boundary probe so no second mount point is needed.
 *
 * <p>
 * Every test here also asserts what happened to the <b>source</b>. Crossing a filesystem boundary is the only situation in which the node copies
 * rather than renames, which makes it the only situation in which two copies exist at once and one of them can be deleted by mistake.
 * </p>
 */
class MoveNodeCrossDeviceTest {

	@TempDir
	File tempDir;

	/**
	 * The shipped default. A worker should not silently spend an hour copying 40 GB because a folder happened to be on another mount.
	 */
	@Test
	void testSkipIsTheDefaultAndTouchesNothing() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		NodeResult result = crossDeviceFolderNode(null, cortexOptions(tempDir), folderOptions(archive)).process(NodeContext.create(media));

		assertThat(result).isSkipped();
		assertEquals(false, result.get(MoveNode.OUT_MOVED));
		assertTrue(Files.exists(Path.of(media.absolutePath())), "the source must be intact");
		assertFalse(Files.exists(archive.toPath().resolve("clip.mp4")), "nothing should have been written");
	}

	/**
	 * 🔴 FAIL must report FAILED, not SUCCESS. {@code NodeContextImpl.next()} reads only the skip reason and drops a recorded failure, which is how a
	 * file mover ends up reporting a green run in which nothing moved.
	 */
	@Test
	void testFailReportsFailedAndTouchesNothing() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		NodeResult result = crossDeviceFolderNode(null, cortexOptions(tempDir), folderOptions(archive).setCrossDevice("FAIL"))
			.process(NodeContext.create(media));

		assertThat(result).isFailed();
		assertEquals("FAILED", result.get(MoveNode.OUT_FLAG));
		assertTrue(Files.exists(Path.of(media.absolutePath())), "the source must be intact");
		assertFalse(Files.exists(archive.toPath().resolve("clip.mp4")), "nothing should have been written");
	}

	@Test
	void testCopyKeepsTheSourceUnderTheDefaultPolicy() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		MoveNodeOptions options = folderOptions(archive).setCrossDevice("COPY").setVerify("SIZE");
		NodeResult result = crossDeviceFolderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals("COPIED", result.get(MoveNode.OUT_FLAG), "a kept source means this was a copy, and it must say so");
		assertTrue(Files.exists(Path.of(media.absolutePath())), "KEEP must leave the source in place");
		assertEquals("payload", Files.readString(archive.toPath().resolve("clip.mp4"), UTF_8));
	}

	@Test
	void testCopyRemovesTheSourceOnlyWhenExplicitlyAsked() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		MoveNodeOptions options = folderOptions(archive)
			.setCrossDevice("COPY")
			.setSourcePolicy("DELETE_AFTER_VERIFY")
			.setVerify("SIZE");
		NodeResult result = crossDeviceFolderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertEquals("MOVED", result.get(MoveNode.OUT_FLAG));
		assertFalse(Files.exists(Path.of(media.absolutePath())), "the source should be gone");
		assertEquals("payload", Files.readString(archive.toPath().resolve("clip.mp4"), UTF_8));
	}

	/**
	 * The SHA-512 verifier proves the copy before the original may be removed. It costs a full read of the copy, which is the right price for
	 * permission to delete the only other copy.
	 */
	@Test
	void testTheDigestVerifierAllowsTheDelete() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		MoveNodeOptions options = folderOptions(archive)
			.setCrossDevice("COPY")
			.setSourcePolicy("DELETE_AFTER_VERIFY")
			.setVerify("SHA512");
		NodeResult result = crossDeviceFolderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(media));

		assertThat(result).isSuccess();
		assertFalse(Files.exists(Path.of(media.absolutePath())), "a verified copy may replace the original");
		assertEquals("payload", Files.readString(archive.toPath().resolve("clip.mp4"), UTF_8));
	}

	/**
	 * No {@code .part} file may survive a successful copy - a leftover would be uploaded and registered by a later sink as if it were an asset.
	 */
	@Test
	void testNoPartialFileIsLeftBehind() throws Exception {
		StubLoomMedia media = mediaWith(tempDir, "clip.mp4", "payload");
		File archive = new File(tempDir, "archive");

		MoveNodeOptions options = folderOptions(archive).setCrossDevice("COPY").setVerify("SIZE");
		crossDeviceFolderNode(null, cortexOptions(tempDir), options).process(NodeContext.create(media));

		try (var files = Files.list(archive.toPath())) {
			assertFalse(files.anyMatch(p -> p.getFileName().toString().contains(".part")), "no .part file may remain");
		}
	}
}
