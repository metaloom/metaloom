package io.metaloom.cli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cli.CliContext;
import io.metaloom.cli.output.OutputFormat;

/**
 * The precedence chain is flag &gt; env &gt; config file &gt; default.
 *
 * <p>Every rung is pinned here because the failure mode is silent: the CLI simply talks to
 * the wrong server, which in an operations tool is worse than an error.</p>
 */
public class CliConfigLoaderTest {

	@TempDir
	Path tempDir;

	private final Map<String, String> env = new HashMap<>();

	private CliConfigLoader loader() {
		return new CliConfigLoader(env::get, tempDir.toString());
	}

	/** Nothing was supplied on the command line. */
	private static final java.util.function.Function<String, Boolean> NOTHING_SUPPLIED = option -> false;

	private CliContext context(Path configFile) {
		CliContext context = new CliContext();
		context.setConfigFile(configFile);
		return context;
	}

	private Path writeConfig(String yaml) throws IOException {
		Path file = tempDir.resolve("cli.yml");
		Files.writeString(file, yaml);
		return file;
	}

	// ── Precedence ───────────────────────────────────────────────────────

	@Test
	@DisplayName("with nothing configured, the built-in default is used")
	void testDefault() {
		CliContext context = context(tempDir.resolve("missing.yml"));

		loader().resolve(context, NOTHING_SUPPLIED);

		assertThat(context.serverUri().toString()).isEqualTo(CliContext.DEFAULT_SERVER);
		assertThat(context.getOutput()).isEqualTo(OutputFormat.TABLE);
		assertThat(context.getTimeout()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	@DisplayName("the config file beats the default")
	void testFileBeatsDefault() throws Exception {
		Path file = writeConfig("""
			currentProfile: default
			profiles:
			  default:
			    server: http://from-file:1111
			    output: json
			    timeout: 5m
			""");
		CliContext context = context(file);

		loader().resolve(context, NOTHING_SUPPLIED);

		assertThat(context.serverUri().toString()).isEqualTo("http://from-file:1111");
		assertThat(context.getOutput()).isEqualTo(OutputFormat.JSON);
		assertThat(context.getTimeout()).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	@DisplayName("the environment beats the config file")
	void testEnvBeatsFile() throws Exception {
		Path file = writeConfig("""
			profiles:
			  default:
			    server: http://from-file:1111
			    output: json
			""");
		env.put(CliConfigLoader.ENV_SERVER, "http://from-env:2222");
		env.put(CliConfigLoader.ENV_OUTPUT, "yaml");
		CliContext context = context(file);

		loader().resolve(context, NOTHING_SUPPLIED);

		assertThat(context.serverUri().toString()).isEqualTo("http://from-env:2222");
		assertThat(context.getOutput()).isEqualTo(OutputFormat.YAML);
	}

	@Test
	@DisplayName("a flag beats everything")
	void testFlagBeatsEnvAndFile() throws Exception {
		Path file = writeConfig("""
			profiles:
			  default:
			    server: http://from-file:1111
			""");
		env.put(CliConfigLoader.ENV_SERVER, "http://from-env:2222");

		CliContext context = context(file);
		context.setServerUrl("http://from-flag:3333");

		// The flag was matched on the command line, so the loader must leave it alone.
		loader().resolve(context, option -> option.equals("--server"));

		assertThat(context.serverUri().toString()).isEqualTo("http://from-flag:3333");
	}

	@Test
	@DisplayName("LOOM_HOST and LOOM_PORT are honoured so one export serves the whole stack")
	void testLoomHostFallback() {
		env.put(CliConfigLoader.ENV_LOOM_HOST, "loom.internal");
		env.put(CliConfigLoader.ENV_LOOM_PORT, "9999");
		CliContext context = context(tempDir.resolve("missing.yml"));

		loader().resolve(context, NOTHING_SUPPLIED);

		assertThat(context.serverUri().toString()).isEqualTo("http://loom.internal:9999");
	}

	@Test
	@DisplayName("METALOOM_SERVER beats the LOOM_HOST pair")
	void testMetaloomServerBeatsLoomHost() {
		env.put(CliConfigLoader.ENV_SERVER, "http://explicit:1234");
		env.put(CliConfigLoader.ENV_LOOM_HOST, "loom.internal");
		CliContext context = context(tempDir.resolve("missing.yml"));

		loader().resolve(context, NOTHING_SUPPLIED);

		assertThat(context.serverUri().toString()).isEqualTo("http://explicit:1234");
	}

	// ── Profiles ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("the file's currentProfile selects which profile is read")
	void testCurrentProfile() throws Exception {
		Path file = writeConfig("""
			currentProfile: prod
			profiles:
			  default:
			    server: http://dev:1111
			  prod:
			    server: http://prod:2222
			""");
		CliContext context = context(file);

		loader().resolve(context, NOTHING_SUPPLIED);

		assertThat(context.profileName()).isEqualTo("prod");
		assertThat(context.serverUri().toString()).isEqualTo("http://prod:2222");
	}

	@Test
	@DisplayName("--profile overrides the file's currentProfile")
	void testProfileFlagOverride() throws Exception {
		Path file = writeConfig("""
			currentProfile: prod
			profiles:
			  dev:
			    server: http://dev:1111
			  prod:
			    server: http://prod:2222
			""");
		CliContext context = context(file);
		context.setProfile("dev");

		loader().resolve(context, option -> option.equals("--profile"));

		assertThat(context.serverUri().toString()).isEqualTo("http://dev:1111");
	}

	@Test
	@DisplayName("an unknown profile falls back to the defaults instead of failing")
	void testUnknownProfile() throws Exception {
		Path file = writeConfig("""
			profiles:
			  default:
			    server: http://dev:1111
			""");
		CliContext context = context(file);
		context.setProfile("nonexistent");

		loader().resolve(context, option -> option.equals("--profile"));

		assertThat(context.serverUri().toString()).isEqualTo(CliContext.DEFAULT_SERVER);
	}

	@Test
	@DisplayName("an unreadable config file is reported, not silently ignored")
	void testMalformedConfig() throws Exception {
		Path file = writeConfig("this: is: not: valid: yaml: at: all:\n  - [");
		CliContext context = context(file);

		assertThatThrownBy(() -> loader().resolve(context, NOTHING_SUPPLIED))
			.isInstanceOf(Exception.class);
	}

	// ── Durations ────────────────────────────────────────────────────────

	@Test
	@DisplayName("durations accept the forms people actually type")
	void testDurationParsing() {
		assertThat(CliConfigLoader.parseDuration("30s")).isEqualTo(Duration.ofSeconds(30));
		assertThat(CliConfigLoader.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
		assertThat(CliConfigLoader.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
		assertThat(CliConfigLoader.parseDuration("500ms")).isEqualTo(Duration.ofMillis(500));
		assertThat(CliConfigLoader.parseDuration("PT45S")).isEqualTo(Duration.ofSeconds(45));
		assertThat(CliConfigLoader.parseDuration("90")).isEqualTo(Duration.ofSeconds(90));
		assertThat(CliConfigLoader.parseDuration(" 1H ")).isEqualTo(Duration.ofHours(1));
	}

	@Test
	@DisplayName("a nonsense duration is rejected with a message that suggests the right form")
	void testInvalidDuration() {
		assertThatThrownBy(() -> CliConfigLoader.parseDuration("soon"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("30s");
	}

	// ── Config file location ─────────────────────────────────────────────

	@Test
	@DisplayName("XDG_CONFIG_HOME is honoured")
	void testXdgConfigHome() {
		env.put("XDG_CONFIG_HOME", "/xdg");
		CliPaths paths = new CliPaths(env::get, "/home/user");

		assertThat(paths.configFile().toString()).isEqualTo("/xdg/metaloom/cli.yml");
		assertThat(paths.credentialsFile().toString()).isEqualTo("/xdg/metaloom/credentials.yml");
	}

	@Test
	@DisplayName("without XDG_CONFIG_HOME the config lands under ~/.config")
	void testDefaultConfigHome() {
		CliPaths paths = new CliPaths(env::get, "/home/user");

		assertThat(paths.configFile().toString()).isEqualTo("/home/user/.config/metaloom/cli.yml");
	}

	@Test
	@DisplayName("METALOOM_CONFIG redirects the config file")
	void testConfigFromEnv() {
		env.put(CliConfigLoader.ENV_CONFIG, "/somewhere/other.yml");

		assertThat(loader().configFile(new CliContext()).toString()).isEqualTo("/somewhere/other.yml");
	}

	@Test
	@DisplayName("--config beats METALOOM_CONFIG")
	void testConfigFlagBeatsEnv() {
		env.put(CliConfigLoader.ENV_CONFIG, "/from/env.yml");
		CliContext context = context(Path.of("/from/flag.yml"));

		assertThat(loader().configFile(context).toString()).isEqualTo("/from/flag.yml");
	}
}
