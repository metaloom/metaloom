package io.metaloom.cortex.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AtomicFilesTest {

	@TempDir
	Path dir;

	/**
	 * The marker goes before the extension, so a downstream muxer still sees the real one.
	 */
	@Test
	public void testPartKeepsTheExtensionLast() {
		assertEquals(dir.resolve("clip.part.mp4"), AtomicFiles.partFor(dir.resolve("clip.mp4")));
		assertEquals(dir.resolve("image.part.png"), AtomicFiles.partFor(dir.resolve("image.png")));
	}

	@Test
	public void testPartOfAnExtensionlessFileAppends() {
		assertEquals(dir.resolve("README.part"), AtomicFiles.partFor(dir.resolve("README")));
	}

	@Test
	public void testMovePublishesTheFile() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.part.mp4"), "payload");
		Path target = dir.resolve("clip.mp4");

		AtomicFiles.move(source, target);

		assertFalse(Files.exists(source), "The temporary file should be gone");
		assertEquals("payload", Files.readString(target, UTF_8));
	}

	@Test
	public void testMoveReplacesAnExistingTarget() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.part.mp4"), "new");
		Path target = Files.writeString(dir.resolve("clip.mp4"), "old");

		AtomicFiles.move(source, target);

		assertEquals("new", Files.readString(target, UTF_8));
		assertTrue(Files.exists(target));
	}
}
