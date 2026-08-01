package io.metaloom.cortex.node.source.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands filesystem path globs into concrete file paths.
 *
 * <p>This is the single implementation of media discovery by path in Cortex.
 * It is used by {@link FilesystemSourceNode} to enumerate its own selection and
 * by callers that need to run a pipeline against an ad-hoc path selection
 * supplied at dispatch time.</p>
 *
 * <h2>Glob semantics</h2>
 * <p>A glob is split at the last path separator before its first wildcard
 * character ({@code * ? [ &#123;}). The prefix becomes the walk root, the remainder
 * becomes a {@code glob:} {@link PathMatcher} evaluated against paths relativized
 * to that root. A glob with no wildcard is treated as a literal path. Roots that
 * do not exist yield no results rather than an error, so a selection spanning
 * several mount points degrades gracefully.</p>
 *
 * <h2>Laziness</h2>
 * <p>The {@code stream} methods walk on demand: the first path is available before
 * the walk has finished, and a consumer that stops early stops the walk. This is what
 * lets source backpressure reach the filesystem — a selection of a million files under
 * a slow mount otherwise has to be enumerated in full, into a list held whole in
 * memory, before the first item can be dispatched.</p>
 *
 * <p><strong>Each returned stream holds an open directory walk and must be closed</strong>
 * — use it in a try-with-resources, or hand it to an operator that closes it
 * ({@code Flowable.fromStream} does, on both completion and cancellation).</p>
 *
 * <p>The {@code expand} and {@code walk} methods return materialised lists and remain
 * for callers that genuinely want the whole selection at once (tests, and ad-hoc
 * selections small enough to count).</p>
 */
public final class FilesystemMediaScanner {

	private static final Logger log = LoggerFactory.getLogger(FilesystemMediaScanner.class);

	private FilesystemMediaScanner() {
	}

	/**
	 * Expand several globs and return the union of their matches.
	 *
	 * <p>Results are de-duplicated while preserving encounter order, so
	 * overlapping globs do not cause a file to be processed twice.</p>
	 *
	 * @param globs the globs to expand; {@code null} and blank entries are ignored
	 * @return distinct matching regular file paths, in encounter order
	 * @throws IOException if walking a root fails
	 */
	public static List<Path> expand(List<String> globs) throws IOException {
		try (Stream<Path> stream = stream(globs)) {
			return stream.toList();
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	/**
	 * Lazily expand several globs into the union of their matches.
	 *
	 * <p>Globs are walked one after another, only as far as the consumer pulls, and
	 * results are de-duplicated while preserving encounter order so overlapping globs do
	 * not process a file twice. De-duplication necessarily remembers what it has already
	 * emitted; that set of paths is the only part of the selection held in memory.</p>
	 *
	 * <p>Walk failures surface as {@link UncheckedIOException} during consumption rather
	 * than at call time, because nothing has been walked yet when this returns.</p>
	 *
	 * @param globs the globs to expand; {@code null} and blank entries are ignored
	 * @return a lazy, distinct stream of matching regular file paths, <b>which the caller
	 *         must close</b>
	 */
	public static Stream<Path> stream(List<String> globs) {
		if (globs == null) {
			return Stream.empty();
		}
		Set<Path> seen = new LinkedHashSet<>();
		return globs.stream()
			.filter(glob -> glob != null && !glob.isBlank())
			// flatMap closes each inner walk once it has been consumed.
			.flatMap(FilesystemMediaScanner::streamUnchecked)
			.filter(seen::add);
	}

	/**
	 * Lazily expand a single glob into the regular files it matches.
	 *
	 * @param glob the glob or literal path to expand
	 * @return a lazy stream of matching regular file paths, empty if the root does not
	 *         exist, <b>which the caller must close</b>
	 * @throws IOException if opening the walk fails
	 */
	public static Stream<Path> stream(String glob) throws IOException {
		int wildIdx = firstWildcardIndex(glob);
		if (wildIdx < 0) {
			Path literal = Paths.get(glob).toAbsolutePath().normalize();
			return Files.isRegularFile(literal) ? Stream.of(literal) : Stream.empty();
		}

		Path root = Paths.get(".").toAbsolutePath().normalize();
		String pattern = glob;
		if (wildIdx > 0) {
			int lastSlash = glob.lastIndexOf('/', wildIdx);
			if (lastSlash > 0) {
				root = Paths.get(glob.substring(0, lastSlash)).toAbsolutePath().normalize();
				pattern = glob.substring(lastSlash + 1);
			}
		}
		if (!Files.exists(root)) {
			log.debug("Glob '{}' resolves to root {} which does not exist — no matches", glob, root);
			return Stream.empty();
		}

		final Path effectiveRoot = root;
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		return Files.walk(effectiveRoot)
			.filter(Files::isRegularFile)
			.filter(p -> matcher.matches(effectiveRoot.relativize(p)));
	}

	/**
	 * Lazily walk a directory root, yielding every regular file beneath it.
	 *
	 * @param root the directory to walk
	 * @return a lazy stream of all regular files under {@code root}, empty if it does not
	 *         exist, <b>which the caller must close</b>
	 * @throws IOException if opening the walk fails
	 */
	public static Stream<Path> stream(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			log.debug("Scan root {} does not exist — no matches", root);
			return Stream.empty();
		}
		if (Files.isRegularFile(root)) {
			return Stream.of(root.toAbsolutePath().normalize());
		}
		return Files.walk(root).filter(Files::isRegularFile);
	}

	private static Stream<Path> streamUnchecked(String glob) {
		try {
			return stream(glob);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to expand path glob " + glob, e);
		}
	}

	/**
	 * Expand a single glob into the regular files it matches.
	 *
	 * @param glob the glob or literal path to expand
	 * @return matching regular file paths, empty if the root does not exist
	 * @throws IOException if walking the root fails
	 */
	public static List<Path> expand(String glob) throws IOException {
		try (Stream<Path> paths = stream(glob)) {
			return paths.toList();
		}
	}

	/**
	 * Walk a directory root and return every regular file beneath it.
	 *
	 * @param root the directory to walk
	 * @return all regular files under {@code root}, empty if it does not exist
	 * @throws IOException if walking fails
	 */
	public static List<Path> walk(Path root) throws IOException {
		try (Stream<Path> paths = stream(root)) {
			return paths.toList();
		}
	}

	/**
	 * Unchecked variant of {@link #expand(List)} for use inside reactive
	 * suppliers, where a checked {@link IOException} cannot be propagated.
	 *
	 * @param globs the globs to expand
	 * @return distinct matching regular file paths
	 * @throws UncheckedIOException if walking a root fails
	 */
	public static List<Path> expandUnchecked(List<String> globs) {
		try {
			return expand(globs);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to expand path globs " + globs, e);
		}
	}

	private static int firstWildcardIndex(String glob) {
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*' || c == '?' || c == '[' || c == '{') {
				return i;
			}
		}
		return -1;
	}
}
