package io.metaloom.cortex.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class PathContainmentTest {

	@Test
	public void testAFileBeneathTheFolderIsInside() {
		assertTrue(PathContainment.isInside(Path.of("/data/dups/clip.mp4"), Path.of("/data/dups")));
	}

	@Test
	public void testANestedFileIsInside() {
		assertTrue(PathContainment.isInside(Path.of("/data/dups/2024/06/clip.mp4"), Path.of("/data/dups")));
	}

	@Test
	public void testTheFolderItselfIsInside() {
		assertTrue(PathContainment.isInside(Path.of("/data/dups"), Path.of("/data/dups")));
	}

	/**
	 * 🔴 The regression this class exists for.
	 *
	 * <p>
	 * The previous implementation normalised both sides and compared them with {@link String#startsWith(String)}, which answers true here. For the
	 * dedup apply node that meant treating an untouched file in {@code dups-old} as already relocated.
	 * </p>
	 */
	@Test
	public void testASiblingFolderWithAMatchingPrefixIsNotInside() {
		assertFalse(PathContainment.isInside(Path.of("/data/dups-old/clip.mp4"), Path.of("/data/dups")));
	}

	@Test
	public void testAnUnrelatedFolderIsNotInside() {
		assertFalse(PathContainment.isInside(Path.of("/data/keep/clip.mp4"), Path.of("/data/dups")));
	}

	@Test
	public void testRelativeSegmentsAreNormalisedBeforeComparing() {
		assertTrue(PathContainment.isInside(Path.of("/data/keep/../dups/clip.mp4"), Path.of("/data/dups")));
		assertFalse(PathContainment.isInside(Path.of("/data/dups/../keep/clip.mp4"), Path.of("/data/dups")));
	}

	@Test
	public void testNullsAreNotInside() {
		assertFalse(PathContainment.isInside((Path) null, Path.of("/data/dups")));
		assertFalse(PathContainment.isInside(Path.of("/data/dups/clip.mp4"), null));
	}
}
