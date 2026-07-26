package io.metaloom.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The colour decision has more inputs than it looks, and getting it wrong means escape
 * codes in a redirected file.
 */
public class AnsiTest {

	private static java.util.function.Function<String, String> env(Map<String, String> values) {
		return values::get;
	}

	@Test
	@DisplayName("auto follows the terminal")
	void testAuto() {
		assertThat(Ansi.resolve(ColorMode.AUTO, env(Map.of()), true).isEnabled()).isTrue();
		assertThat(Ansi.resolve(ColorMode.AUTO, env(Map.of()), false).isEnabled()).isFalse();
	}

	@Test
	@DisplayName("never wins over a terminal")
	void testNever() {
		assertThat(Ansi.resolve(ColorMode.NEVER, env(Map.of()), true).isEnabled()).isFalse();
	}

	@Test
	@DisplayName("always wins over a non-terminal")
	void testAlways() {
		assertThat(Ansi.resolve(ColorMode.ALWAYS, env(Map.of()), false).isEnabled()).isTrue();
	}

	@Test
	@DisplayName("NO_COLOR beats even --color=always")
	void testNoColorBeatsAlways() {
		// An environment opt-out belongs to the person running the command; a script's
		// --color=always should not override their terminal preference.
		assertThat(Ansi.resolve(ColorMode.ALWAYS, env(Map.of("NO_COLOR", "1")), true).isEnabled()).isFalse();
		assertThat(Ansi.resolve(ColorMode.AUTO, env(Map.of("NO_COLOR", "1")), true).isEnabled()).isFalse();
	}

	@Test
	@DisplayName("an empty NO_COLOR is not an opt-out")
	void testEmptyNoColorIgnored() {
		// no-color.org: the variable must be *set and non-empty*. Treating "" as an opt-out
		// would disable colour for anyone with a stray `export NO_COLOR=`.
		assertThat(Ansi.resolve(ColorMode.AUTO, env(Map.of("NO_COLOR", "")), true).isEnabled()).isTrue();
	}

	@Test
	@DisplayName("TERM=dumb disables colour in auto mode")
	void testDumbTerminal() {
		assertThat(Ansi.resolve(ColorMode.AUTO, env(Map.of("TERM", "dumb")), true).isEnabled()).isFalse();
		// but not when explicitly asked for
		assertThat(Ansi.resolve(ColorMode.ALWAYS, env(Map.of("TERM", "dumb")), true).isEnabled()).isTrue();
	}

	@Test
	@DisplayName("a disabled Ansi emits no escape sequences at all")
	void testDisabledIsPlain() {
		Ansi ansi = new Ansi(false);

		assertThat(ansi.green("x")).isEqualTo("x");
		assertThat(ansi.status("SUCCESS")).isEqualTo("SUCCESS");
		assertThat(ansi.bold(ansi.red("x"))).isEqualTo("x");
	}

	@Test
	@DisplayName("status colouring covers the run and health vocabularies")
	void testStatusVocabulary() {
		Ansi ansi = new Ansi(true);

		// The point is that every status the server can emit is recognised; an unknown one
		// falls through uncoloured rather than breaking.
		for (String status : new String[] { "SUCCESS", "FAILED", "PARTIAL", "PAUSED", "CANCELLED",
			"RUNNING", "PENDING", "UP", "DEGRADED", "COMPLETED" }) {
			assertThat(ansi.status(status)).as(status).contains("").contains(status);
		}
		assertThat(ansi.status("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
		assertThat(ansi.status(null)).isEmpty();
	}
}
