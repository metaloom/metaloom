package io.metaloom.cortex.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.fs.Conflicts.ConflictException;

public class ConflictsTest {

	@TempDir
	Path dir;

	@Test
	public void testAFreeDestinationIsUsedAsIs() {
		Path target = dir.resolve("clip.mp4");
		assertEquals(target, Conflicts.resolve(target, ConflictPolicy.SUFFIX).orElseThrow());
	}

	@Test
	public void testSuffixNumbersUpwards() throws IOException {
		Path target = dir.resolve("clip.mp4");
		Files.createFile(target);
		assertEquals(dir.resolve("clip_1.mp4"), Conflicts.resolve(target, ConflictPolicy.SUFFIX).orElseThrow());

		Files.createFile(dir.resolve("clip_1.mp4"));
		assertEquals(dir.resolve("clip_2.mp4"), Conflicts.resolve(target, ConflictPolicy.SUFFIX).orElseThrow());
	}

	@Test
	public void testSuffixKeepsTheExtensionLast() throws IOException {
		Path target = dir.resolve("archive.tar.gz");
		Files.createFile(target);
		assertEquals(dir.resolve("archive.tar_1.gz"), Conflicts.resolve(target, ConflictPolicy.SUFFIX).orElseThrow());
	}

	@Test
	public void testSuffixHandlesAFileWithoutAnExtension() throws IOException {
		Path target = dir.resolve("README");
		Files.createFile(target);
		assertEquals(dir.resolve("README_1"), Conflicts.resolve(target, ConflictPolicy.SUFFIX).orElseThrow());
	}

	@Test
	public void testSkipAnswersEmpty() throws IOException {
		Path target = dir.resolve("clip.mp4");
		Files.createFile(target);
		assertEquals(Optional.empty(), Conflicts.resolve(target, ConflictPolicy.SKIP));
	}

	@Test
	public void testFailThrowsNamingTheDestination() throws IOException {
		Path target = dir.resolve("clip.mp4");
		Files.createFile(target);
		ConflictException e = assertThrows(ConflictException.class, () -> Conflicts.resolve(target, ConflictPolicy.FAIL));
		assertTrue(e.getMessage().contains("clip.mp4"), "The message should name the destination, was: " + e.getMessage());
	}

	/**
	 * The ceiling is a reported failure, not a hang and not a stack trace from somewhere else.
	 */
	@Test
	public void testTheAttemptCeilingIsReported() throws IOException {
		Path target = dir.resolve("clip.mp4");
		Files.createFile(target);
		for (int i = 1; i < Conflicts.MAX_ATTEMPTS; i++) {
			Files.createFile(dir.resolve("clip_" + i + ".mp4"));
		}
		ConflictException e = assertThrows(ConflictException.class, () -> Conflicts.resolve(target, ConflictPolicy.SUFFIX));
		assertTrue(e.getMessage().contains(String.valueOf(Conflicts.MAX_ATTEMPTS)), "The message should name the ceiling, was: " + e.getMessage());
	}

	@Test
	public void testAnUnknownPolicyNameIsRejectedWithTheAcceptedValues() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ConflictPolicy.parse("OVERWRITE"));
		assertTrue(e.getMessage().contains("SUFFIX"), "The message should list the accepted values, was: " + e.getMessage());
	}
}
