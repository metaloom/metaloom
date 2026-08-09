package io.metaloom.cortex.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileStoresTest {

	@TempDir
	Path dir;

	@Test
	public void testTwoPathsInTheSameTreeShareAStore() throws IOException {
		Path a = Files.writeString(dir.resolve("a.txt"), "a");
		Path b = Files.writeString(dir.resolve("b.txt"), "b");
		assertTrue(FileStores.sameStore(a, b));
	}

	/**
	 * The destination of a move does not exist yet, and neither does its parent on a first run. Resolving via the nearest existing ancestor is what
	 * makes the check usable <em>before</em> the move rather than after it.
	 */
	@Test
	public void testANonExistentDestinationResolvesViaItsNearestExistingAncestor() throws IOException {
		Path source = Files.writeString(dir.resolve("source.txt"), "payload");
		Path deepTarget = dir.resolve("trash/2024/06/source.txt");

		assertFalse(Files.exists(deepTarget.getParent()), "The test needs the destination tree to be absent");
		assertTrue(FileStores.sameStore(source, deepTarget), "A path under the same tree is on the same store even before it exists");
	}

	/**
	 * The ancestor walk always terminates at the filesystem root, which exists, so a wholly absent destination still resolves - to the store that
	 * would actually hold it. That is the useful answer: creating {@code /archive/...} on a box where {@code /archive} is not a mount really would
	 * write to the root filesystem.
	 */
	@Test
	public void testAnAbsentDestinationResolvesToTheStoreThatWouldHoldIt() throws IOException {
		Path absent = Path.of("/nonexistent-root-" + System.nanoTime() + "/x");
		assertNotNull(FileStores.storeOf(absent), "The walk should reach the root, which exists");
		assertEquals(FileStores.storeOf(Path.of("/")), FileStores.storeOf(absent));
	}

	/**
	 * A null path has no store. The guard exists because {@link FileStores#sameStore} must answer "not the same" rather than throw when it cannot
	 * resolve a side - answering "same" would send the caller into an atomic move that then fails, instead of into its cross-device policy.
	 */
	@Test
	public void testANullPathHasNoStore() throws IOException {
		assertNull(FileStores.storeOf(null));
		assertFalse(FileStores.sameStore(dir, null));
	}

	@Test
	public void testDescribeNamesTheStore() throws IOException {
		assertNotNull(FileStores.storeOf(dir));
		String description = FileStores.describeStoreOf(dir);
		assertFalse(description.isBlank(), "A store description should not be blank");
	}

	@Test
	public void testDescribeToleratesAnUnknownStore() {
		assertTrue(FileStores.describe(null).contains("unknown"));
	}
}
