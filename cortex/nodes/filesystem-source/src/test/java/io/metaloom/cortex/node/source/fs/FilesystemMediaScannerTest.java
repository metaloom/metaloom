package io.metaloom.cortex.node.source.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the glob/walk expansion that backs the {@code filesystem-source} node.
 */
public class FilesystemMediaScannerTest {

	@TempDir
	Path root;

	private Path videoA;
	private Path videoB;
	private Path nestedVideo;
	private Path textFile;

	@BeforeEach
	public void setupTree() throws IOException {
		videoA = Files.writeString(root.resolve("a.mp4"), "a");
		videoB = Files.writeString(root.resolve("b.mp4"), "b");
		textFile = Files.writeString(root.resolve("notes.txt"), "notes");

		Path nested = Files.createDirectories(root.resolve("nested/deeper"));
		nestedVideo = Files.writeString(nested.resolve("c.mp4"), "c");
	}

	@Test
	public void testExpandFlatGlobMatchesOnlyTopLevel() throws IOException {
		List<Path> found = FilesystemMediaScanner.expand(root + "/*.mp4");
		assertThat(found).containsExactlyInAnyOrder(videoA, videoB);
	}

	@Test
	public void testExpandRecursiveGlobMatchesNestedFiles() throws IOException {
		List<Path> found = FilesystemMediaScanner.expand(root + "/**.mp4");
		assertThat(found).contains(nestedVideo);
	}

	@Test
	public void testExpandDoesNotMatchOtherExtensions() throws IOException {
		List<Path> found = FilesystemMediaScanner.expand(root + "/*.mp4");
		assertThat(found).doesNotContain(textFile);
	}

	@Test
	public void testExpandLiteralPathResolvesToThatFile() throws IOException {
		List<Path> found = FilesystemMediaScanner.expand(videoA.toString());
		assertThat(found).containsExactly(videoA);
	}

	@Test
	public void testExpandLiteralDirectoryYieldsNothing() throws IOException {
		// A literal path is only honoured when it is a regular file — a bare
		// directory must not silently expand to its contents.
		assertThat(FilesystemMediaScanner.expand(root.toString())).isEmpty();
	}

	@Test
	public void testExpandMissingRootYieldsNothingRatherThanThrowing() throws IOException {
		assertThat(FilesystemMediaScanner.expand(root + "/does-not-exist/*.mp4")).isEmpty();
		assertThat(FilesystemMediaScanner.expand(root + "/missing.mp4")).isEmpty();
	}

	@Test
	public void testExpandDeduplicatesOverlappingGlobs() throws IOException {
		// Both globs match a.mp4; it must be processed once, not twice.
		List<Path> found = FilesystemMediaScanner.expand(List.of(root + "/*.mp4", root + "/a.*"));
		assertThat(found).containsExactlyInAnyOrder(videoA, videoB);
	}

	@Test
	public void testExpandIgnoresNullAndBlankGlobs() throws IOException {
		List<Path> found = FilesystemMediaScanner.expand(java.util.Arrays.asList(root + "/*.mp4", null, "  ", ""));
		assertThat(found).containsExactlyInAnyOrder(videoA, videoB);
	}

	@Test
	public void testExpandNullListYieldsNothing() throws IOException {
		assertThat(FilesystemMediaScanner.expand((List<String>) null)).isEmpty();
	}

	@Test
	public void testWalkReturnsAllRegularFilesRecursively() throws IOException {
		assertThat(FilesystemMediaScanner.walk(root))
			.containsExactlyInAnyOrder(videoA, videoB, textFile, nestedVideo);
	}

	@Test
	public void testWalkOnRegularFileReturnsThatFile() throws IOException {
		assertThat(FilesystemMediaScanner.walk(videoA)).containsExactly(videoA);
	}

	@Test
	public void testWalkMissingRootYieldsNothing() throws IOException {
		assertThat(FilesystemMediaScanner.walk(root.resolve("nope"))).isEmpty();
		assertThat(FilesystemMediaScanner.walk(null)).isEmpty();
	}

	// ── Laziness ──────────────────────────────────────────────────────────
	//
	// The point of the stream methods is that a consumer which stops early stops the
	// walk. Asserting only on the contents would pass just as well for a materialised
	// list, so these assert on how much was walked, not on what came back.

	@Test
	public void testStreamStopsWalkingWhenTheConsumerStops() throws IOException {
		Path deep = Files.createDirectories(root.resolve("many"));
		for (int i = 0; i < 200; i++) {
			Files.writeString(deep.resolve("f" + i + ".mp4"), "x");
		}

		AtomicInteger visited = new AtomicInteger();
		List<Path> firstThree;
		try (Stream<Path> stream = FilesystemMediaScanner.stream(List.of(deep + "/*.mp4"))) {
			firstThree = stream.peek(p -> visited.incrementAndGet()).limit(3).toList();
		}

		assertThat(firstThree).hasSize(3);
		assertThat(visited.get())
			.as("a short-circuiting consumer must not pull the whole selection")
			.isLessThan(200);
	}

	@Test
	public void testStreamDeduplicatesOverlappingGlobsInEncounterOrder() throws IOException {
		try (Stream<Path> stream = FilesystemMediaScanner.stream(List.of(root + "/*.mp4", root + "/a.*"))) {
			assertThat(stream.toList()).containsExactlyInAnyOrder(videoA, videoB);
		}
	}

	@Test
	public void testStreamSkipsNullAndBlankGlobs() throws IOException {
		try (Stream<Path> stream = FilesystemMediaScanner.stream(java.util.Arrays.asList(root + "/*.mp4", null, "  ", ""))) {
			assertThat(stream.toList()).containsExactlyInAnyOrder(videoA, videoB);
		}
	}

	@Test
	public void testStreamNullListYieldsNothing() {
		try (Stream<Path> stream = FilesystemMediaScanner.stream((List<String>) null)) {
			assertThat(stream.toList()).isEmpty();
		}
	}

	@Test
	public void testStreamOfRootWalksRecursively() throws IOException {
		try (Stream<Path> stream = FilesystemMediaScanner.stream(root)) {
			assertThat(stream.toList()).containsExactlyInAnyOrder(videoA, videoB, textFile, nestedVideo);
		}
	}

	// ── Walk depth ────────────────────────────────────────────────────────
	//
	// Only ** crosses separators, so a flat glob can only match directly under its root.
	// Walking further is both wasted work on a large tree and a way for an unrelated
	// directory to break the selection.

	@Test
	public void testAFlatGlobDoesNotDescendIntoSubdirectories() throws IOException {
		// An unreadable directory below the root is the observable proof: Files.walk would throw
		// AccessDeniedException the moment it tried to open it, and a flat glob has no business
		// going there at all.
		Path locked = Files.createDirectory(root.resolve("locked"));
		assumeCanRevokeAccess(locked);
		try {
			assertThat(FilesystemMediaScanner.expand(root + "/*.mp4"))
				.containsExactlyInAnyOrder(videoA, videoB);
		} finally {
			// Give it back, or @TempDir cannot clean up afterwards.
			Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	public void testAGlobWithOneSubdirectoryLevelWalksExactlyThatDeep() throws IOException {
		Path level = Files.createDirectories(root.resolve("nested"));
		Path clip = Files.writeString(level.resolve("d.mp4"), "d");
		// "nested/deeper" is one level further down, and its content must stay out.
		assertThat(FilesystemMediaScanner.expand(root + "/nested/*.mp4")).containsExactly(clip);
	}

	@Test
	public void testARecursiveGlobStillReachesTheDeepestLevel() throws IOException {
		assertThat(FilesystemMediaScanner.expand(root + "/**/*.mp4")).contains(nestedVideo);
	}

	// ── Links ─────────────────────────────────────────────────────────────

	@Test
	public void testASymlinkedFileIsMatched() throws IOException {
		Path link = root.resolve("link.mp4");
		try {
			Files.createSymbolicLink(link, nestedVideo);
		} catch (IOException | UnsupportedOperationException e) {
			return; // no symlink support here; nothing to pin
		}
		assertThat(FilesystemMediaScanner.expand(root + "/*.mp4"))
			.containsExactlyInAnyOrder(videoA, videoB, link);
	}

	@Test
	public void testASymlinkLoopDoesNotHangARecursiveWalk() throws IOException {
		Path loop = root.resolve("nested/loop");
		try {
			Files.createSymbolicLink(loop, root);
		} catch (IOException | UnsupportedOperationException e) {
			return; // no symlink support here; nothing to pin
		}
		// Files.walk does not follow links, which is what makes this terminate.
		assertThat(FilesystemMediaScanner.expand(root + "/**/*.mp4")).contains(nestedVideo);
	}

	/**
	 * Revoke read/execute on a directory, skipping the test when the filesystem or the user makes
	 * that impossible (root ignores the bits entirely).
	 */
	private static void assumeCanRevokeAccess(Path directory) throws IOException {
		try {
			Files.setPosixFilePermissions(directory, java.util.Set.of());
		} catch (UnsupportedOperationException e) {
			org.junit.jupiter.api.Assumptions.abort("POSIX permissions are not supported here");
		}
		try (Stream<Path> listing = Files.list(directory)) {
			listing.findAny();
			Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			org.junit.jupiter.api.Assumptions.abort("this user can read a 000 directory (running as root?)");
		} catch (IOException expected) {
			// Good: opening it really does fail, so the assertion below means something.
		}
	}
}
