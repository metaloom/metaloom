package io.metaloom.cortex.node.consistency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.pipeline.test.StubLoomMedia;

/**
 * The consistency node's skip cache must not outlive the bytes it judged.
 *
 * <p>
 * Its whole job is to notice a file that is still being written. Keyed by path, the first "incomplete" verdict on a half-copied file was replayed
 * for the worker's lifetime, so the finished file never became complete.
 * </p>
 */
class ConsistencyNodeCacheKeyTest {

	@TempDir
	File tempDir;

	@Test
	void testTheKeyChangesWhenTheFileGrows() throws Exception {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "half");
		String whileCopying = ConsistencyNode.cacheKey(media);

		Files.writeString(media.file().toPath(), " and the rest", StandardOpenOption.APPEND);

		assertNotEquals(whileCopying, ConsistencyNode.cacheKey(media));
	}

	@Test
	void testTheKeyIsStableForAnUnchangedFile() {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "whole");
		assertEquals(ConsistencyNode.cacheKey(media), ConsistencyNode.cacheKey(media));
	}
}
