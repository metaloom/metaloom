package io.metaloom.cortex.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.fs.MoveOutcome.State;

public class LocalMoverTest {

	@TempDir
	Path dir;

	/** A mover that believes every destination is on another filesystem, so the copy paths can be exercised without a second mount. */
	private final LocalMover crossDeviceMover = new LocalMover((a, b) -> false);

	/** The real thing, which on a temp dir renames. */
	private final LocalMover sameDeviceMover = new LocalMover();

	// --- same filesystem ---

	@Test
	public void testASameStoreMoveRenames() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/clip.mp4");

		MoveOutcome outcome = sameDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.SKIP, true, null);

		assertEquals(State.MOVED, outcome.state());
		assertFalse(outcome.crossDevice());
		assertFalse(Files.exists(source), "The source should be gone after a rename");
		assertEquals("payload", Files.readString(target, UTF_8));
	}

	@Test
	public void testTheDestinationTreeIsCreated() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/2024/06/clip.mp4");

		sameDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.SKIP, true, null);

		assertTrue(Files.exists(target));
	}

	// --- cross-device policies ---

	@Test
	public void testCrossDeviceSkipTouchesNothing() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/clip.mp4");

		MoveOutcome outcome = crossDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.SKIP, true, null);

		assertTrue(outcome.isSkipped());
		assertTrue(Files.exists(source), "The source must be intact");
		assertFalse(Files.exists(target), "Nothing should have been written");
	}

	@Test
	public void testCrossDeviceFailThrowsAndTouchesNothing() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/clip.mp4");

		assertThrows(IOException.class,
			() -> crossDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.FAIL, true, null));

		assertTrue(Files.exists(source), "The source must be intact");
		assertFalse(Files.exists(target), "Nothing should have been written");
	}

	@Test
	public void testCrossDeviceCopyRemovesTheSourceOnlyWhenAsked() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/clip.mp4");

		MoveOutcome kept = crossDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.COPY, false, LocalMover.SIZE_VERIFIER);

		assertEquals(State.COPIED, kept.state());
		assertTrue(kept.crossDevice());
		assertFalse(kept.sourceRemoved());
		assertTrue(Files.exists(source), "KEEP must leave the source in place");
		assertEquals("payload", Files.readString(target, UTF_8));

		Path secondTarget = dir.resolve("trash2/clip.mp4");
		MoveOutcome removed = crossDeviceMover.move(source, secondTarget, ConflictPolicy.SUFFIX, CrossDevicePolicy.COPY, true,
			LocalMover.SIZE_VERIFIER);

		assertEquals(State.MOVED, removed.state());
		assertTrue(removed.sourceRemoved());
		assertFalse(Files.exists(source), "DELETE_AFTER_VERIFY must remove the source");
	}

	/**
	 * 🔴 The invariant that matters most: a destination that does not verify costs time, never bytes.
	 */
	@Test
	public void testAFailedVerifyRemovesTheDestinationAndKeepsTheSource() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/clip.mp4");

		assertThrows(IOException.class, () -> crossDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.COPY, true,
			(s, t) -> false));

		assertTrue(Files.exists(source), "The source must survive a failed verify");
		assertEquals("payload", Files.readString(source, UTF_8), "The source must be unmodified");
		assertFalse(Files.exists(target), "The unverified destination must be removed");
	}

	/**
	 * A copy publishes through a {@code .part} sibling, so an interrupted run never leaves a visible truncated file. After a successful move no
	 * {@code .part} may remain either.
	 */
	@Test
	public void testNoPartFileSurvivesASuccessfulCopy() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "payload");
		Path target = dir.resolve("trash/clip.mp4");

		crossDeviceMover.move(source, target, ConflictPolicy.SUFFIX, CrossDevicePolicy.COPY, true, LocalMover.SIZE_VERIFIER);

		try (Stream<Path> files = Files.list(target.getParent())) {
			assertFalse(files.anyMatch(p -> p.getFileName().toString().contains(".part")), "No .part file may be left behind");
		}
	}

	// --- conflicts ---

	@Test
	public void testAnOccupiedDestinationIsSuffixedAndNeverOverwritten() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "new");
		Files.createDirectories(dir.resolve("trash"));
		Path occupant = Files.writeString(dir.resolve("trash/clip.mp4"), "existing");

		MoveOutcome outcome = sameDeviceMover.move(source, dir.resolve("trash/clip.mp4"), ConflictPolicy.SUFFIX, CrossDevicePolicy.SKIP, true, null);

		assertEquals(dir.resolve("trash/clip_1.mp4"), outcome.target());
		assertEquals("existing", Files.readString(occupant, UTF_8), "The occupant must be untouched");
		assertEquals("new", Files.readString(outcome.target(), UTF_8));
	}

	@Test
	public void testConflictSkipLeavesBothFilesAlone() throws IOException {
		Path source = Files.writeString(dir.resolve("clip.mp4"), "new");
		Files.createDirectories(dir.resolve("trash"));
		Path occupant = Files.writeString(dir.resolve("trash/clip.mp4"), "existing");

		MoveOutcome outcome = sameDeviceMover.move(source, dir.resolve("trash/clip.mp4"), ConflictPolicy.SKIP, CrossDevicePolicy.SKIP, true, null);

		assertTrue(outcome.isSkipped());
		assertTrue(Files.exists(source), "The source must be intact");
		assertEquals("existing", Files.readString(occupant, UTF_8), "The occupant must be untouched");
	}

	@Test
	public void testMovingSomethingThatIsNotAFileFails() {
		assertThrows(IOException.class,
			() -> sameDeviceMover.move(dir.resolve("missing.mp4"), dir.resolve("trash/missing.mp4"), ConflictPolicy.SUFFIX,
				CrossDevicePolicy.SKIP, true, null));
	}
}
