package io.metaloom.cortex.node.source.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
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
		Set<Path> matches = new LinkedHashSet<>();
		if (globs == null) {
			return List.of();
		}
		for (String glob : globs) {
			if (glob == null || glob.isBlank()) {
				continue;
			}
			matches.addAll(expand(glob));
		}
		return new ArrayList<>(matches);
	}

	/**
	 * Expand a single glob into the regular files it matches.
	 *
	 * @param glob the glob or literal path to expand
	 * @return matching regular file paths, empty if the root does not exist
	 * @throws IOException if walking the root fails
	 */
	public static List<Path> expand(String glob) throws IOException {
		int wildIdx = firstWildcardIndex(glob);
		if (wildIdx < 0) {
			Path literal = Paths.get(glob).toAbsolutePath().normalize();
			return Files.isRegularFile(literal) ? List.of(literal) : List.of();
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
			return List.of();
		}

		final Path effectiveRoot = root;
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		try (Stream<Path> stream = Files.walk(effectiveRoot)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(p -> matcher.matches(effectiveRoot.relativize(p)))
				.toList();
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
		if (root == null || !Files.exists(root)) {
			log.debug("Scan root {} does not exist — no matches", root);
			return List.of();
		}
		if (Files.isRegularFile(root)) {
			return List.of(root.toAbsolutePath().normalize());
		}
		try (Stream<Path> stream = Files.walk(root)) {
			return stream.filter(Files::isRegularFile).toList();
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
