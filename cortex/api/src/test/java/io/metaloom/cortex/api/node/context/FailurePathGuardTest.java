package io.metaloom.cortex.api.node.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The guard that stops {@code ctx.failure(cause).next()} from coming back.
 *
 * <p>
 * {@code NodeContextImpl.next()} used to read only {@code skipReason}, so a node that recorded a
 * failure cause and then called {@code next()} produced a <em>SUCCESS</em> result with a null
 * message. Fifteen call sites across thirteen production node classes did it, and a run whose
 * transcript, thumbnail or fingerprint had failed was indistinguishable from one that worked.
 * {@code next()} is now fail-closed and yields {@code FAILED} in that case, but the shape is still
 * wrong: the intent belongs in the node, where a reader of the catch block can see it, and the two
 * spellings drifting apart is how the defect survived so long in the first place.
 * </p>
 *
 * <p>
 * <strong>Why a source scan and not ArchUnit.</strong> ArchUnit reasons over bytecode, where this
 * pattern is not expressible: after compilation {@code failure(...).next()} and
 * {@code failure(...); ...; next()} are the same two invocations of the same two interface methods
 * on the same receiver, and a rule broad enough to catch the first ("a method that calls both")
 * flags every correct {@code abort()} path that also has a skip branch. The offence is a
 * <em>source</em> shape, so it is checked against source. The scan is deliberately narrow — it
 * matches the chained call only — and comments and string literals are stripped first so that a
 * doc comment explaining the anti-pattern (there are several, including this one) does not fail the
 * build.
 * </p>
 */
class FailurePathGuardTest {

	/** From this module's directory to the Cortex tree that holds every node. */
	private static final Path CORTEX = Path.of("..");

	@Test
	void testNoNodeTerminatesAFailureWithNext() throws IOException {
		List<String> offenders = new ArrayList<>();

		try (Stream<Path> tree = Files.walk(CORTEX)) {
			List<Path> sources = tree
				.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".java"))
				.filter(FailurePathGuardTest::isMainSource)
				.sorted()
				.toList();

			// A scan that silently found nothing would pass forever. Cortex has hundreds of main sources;
			// if this trips, the working directory is not the module directory and the guard is inert.
			assertThat(sources)
				.as("Cortex main sources reachable from " + CORTEX.toAbsolutePath().normalize())
				.hasSizeGreaterThan(100);

			for (Path source : sources) {
				String code = stripCommentsAndStrings(Files.readString(source, StandardCharsets.UTF_8));
				for (int line : chainedFailureNextLines(code)) {
					offenders.add(CORTEX.toAbsolutePath().normalize().relativize(source.toAbsolutePath().normalize()) + ":" + line);
				}
			}
		}

		assertThat(offenders)
			.as("ctx.failure(cause).next() reports the item as processed and buries the cause. "
				+ "Terminate a failure with ctx.failure(cause).abort(); if the item is merely not applicable, "
				+ "that is ctx.skipped(reason).next(). See DominantColorNode for the worked example")
			.isEmpty();
	}

	/**
	 * The guard has to be able to fail. This feeds it the exact shape it hunts for, so a refactor that
	 * breaks the matcher is caught here rather than by the offence it stopped noticing.
	 */
	@Test
	void testTheGuardActuallyMatchesTheOffendingShape() {
		String offending = """
			class Node {
				NodeResult compute(NodeContext<LoomMedia> ctx) {
					try {
						return ctx.next();
					} catch (Exception e) {
						return ctx.failure(e.getMessage()).next();
					}
				}
			}
			""";
		assertThat(chainedFailureNextLines(stripCommentsAndStrings(offending))).containsExactly(6);

		String correct = offending.replace("failure(e.getMessage()).next()", "failure(e.getMessage()).abort()");
		assertThat(chainedFailureNextLines(stripCommentsAndStrings(correct))).isEmpty();
	}

	/**
	 * A doc comment or a log message that quotes the anti-pattern must not fail the build — several in
	 * this tree do, on purpose, to explain it.
	 */
	@Test
	void testCommentsAndStringsAreNotCode() {
		assertThat(chainedFailureNextLines(stripCommentsAndStrings(
			"// never write ctx.failure(msg).next()\n"
				+ "/* ctx.failure(msg).next() reports SUCCESS */\n"
				+ "String hint = \"use ctx.failure(msg).abort(), not ctx.failure(msg).next()\";\n")))
					.isEmpty();
	}

	private static boolean isMainSource(Path path) {
		String p = path.toString().replace('\\', '/');
		return p.contains("/src/main/java/") && !p.contains("/target/");
	}

	/**
	 * Find every {@code failure(...)} whose argument list is closed and immediately followed by
	 * {@code .next()}. The argument list is matched by counting parentheses rather than by a regex, so
	 * a nested call in the cause message ({@code failure("x: " + e.getMessage())}) is handled.
	 *
	 * @return the 1-based line numbers of the offending calls
	 */
	private static List<Integer> chainedFailureNextLines(String code) {
		List<Integer> lines = new ArrayList<>();
		int from = 0;
		while (true) {
			int at = code.indexOf("failure(", from);
			if (at < 0) {
				return lines;
			}
			from = at + "failure(".length();
			// Only a call on something, i.e. `ctx.failure(` - never a declaration of the method itself.
			if (at == 0 || code.charAt(at - 1) != '.') {
				continue;
			}
			int close = closingParen(code, from);
			if (close < 0) {
				continue;
			}
			if (nextCallIs(code, close + 1, "next")) {
				lines.add(lineOf(code, at));
			}
		}
	}

	/** @return the index of the {@code )} closing the list opened just before {@code from}, or -1 */
	private static int closingParen(String code, int from) {
		int depth = 1;
		for (int i = from; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** Whether the next thing after {@code from}, ignoring whitespace, is {@code .name(}. */
	private static boolean nextCallIs(String code, int from, String name) {
		int i = from;
		while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
			i++;
		}
		if (i >= code.length() || code.charAt(i) != '.') {
			return false;
		}
		i++;
		while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
			i++;
		}
		return code.startsWith(name, i) && code.substring(i + name.length()).stripLeading().startsWith("(");
	}

	private static int lineOf(String code, int index) {
		int line = 1;
		for (int i = 0; i < index; i++) {
			if (code.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	/**
	 * Blank out comments and string/char literals while preserving every newline, so the reported line
	 * numbers still point at the real source line.
	 */
	private static String stripCommentsAndStrings(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int i = 0;
		while (i < source.length()) {
			char c = source.charAt(i);
			if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
				while (i < source.length() && source.charAt(i) != '\n') {
					out.append(' ');
					i++;
				}
			} else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
				while (i < source.length() && !(source.charAt(i) == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/')) {
					out.append(source.charAt(i) == '\n' ? '\n' : ' ');
					i++;
				}
				i = Math.min(i + 2, source.length());
				out.append("  ");
			} else if (c == '"' || c == '\'') {
				// Text blocks are `"""` - closed by the same three quotes, so the same scan works if the
				// opener consumes all three.
				String quote = source.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(c);
				out.append(" ".repeat(quote.length()));
				i += quote.length();
				while (i < source.length() && !source.startsWith(quote, i)) {
					if (source.charAt(i) == '\\') {
						out.append("  ");
						i += 2;
						continue;
					}
					out.append(source.charAt(i) == '\n' ? '\n' : ' ');
					i++;
				}
				out.append(" ".repeat(quote.length()));
				i = Math.min(i + quote.length(), source.length());
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}
}
