package io.metaloom.cli.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.function.Function;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.CliContext;
import io.metaloom.cli.output.CliJson;
import io.metaloom.cli.output.ColorMode;
import io.metaloom.cli.output.OutputFormat;

/**
 * Applies the settings precedence chain: <strong>flag &gt; env &gt; config file &gt;
 * default</strong>.
 *
 * <p>Runs once per invocation, after picocli has parsed but before the command executes.
 * "Was this flag actually given?" is answered by picocli's {@code ParseResult} rather than
 * by null-checking the context, because a null field cannot distinguish "not supplied" from
 * "supplied as null" once defaults are involved.</p>
 */
@Singleton
public class CliConfigLoader {

	public static final String ENV_SERVER = "METALOOM_SERVER";
	public static final String ENV_PROFILE = "METALOOM_PROFILE";
	public static final String ENV_TOKEN = "METALOOM_TOKEN";
	public static final String ENV_OUTPUT = "METALOOM_OUTPUT";
	public static final String ENV_TIMEOUT = "METALOOM_TIMEOUT";
	public static final String ENV_CONFIG = "METALOOM_CONFIG";
	/** Cortex and the server already use these two; honour them so one export covers both. */
	public static final String ENV_LOOM_HOST = "LOOM_HOST";
	public static final String ENV_LOOM_PORT = "LOOM_PORT";

	private final Function<String, String> env;
	private final String userHome;

	@Inject
	public CliConfigLoader() {
		this(System::getenv, System.getProperty("user.home", "."));
	}

	public CliConfigLoader(Function<String, String> env, String userHome) {
		this.env = env;
		this.userHome = userHome;
	}

	public CliPaths paths(CliContext context) {
		return new CliPaths(env, userHome);
	}

	/** @return the config file in use, honouring {@code --config} and {@code METALOOM_CONFIG} */
	public Path configFile(CliContext context) {
		if (context.getConfigFile() != null) {
			return context.getConfigFile();
		}
		String fromEnv = env.apply(ENV_CONFIG);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return Paths.get(fromEnv);
		}
		return new CliPaths(env, userHome).configFile();
	}

	public CliConfigFile read(Path file) {
		if (file == null || !Files.exists(file)) {
			return new CliConfigFile();
		}
		try {
			String content = Files.readString(file);
			if (content.isBlank()) {
				return new CliConfigFile();
			}
			return CliJson.yaml().readValue(content, CliConfigFile.class);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	public void write(Path file, CliConfigFile config) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, CliJson.yaml().writeValueAsString(config));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	/**
	 * Fill in everything the command line did not supply.
	 *
	 * @param context  the context already carrying the parsed flags
	 * @param supplied tells whether a given long option name was present on the command line
	 */
	public void resolve(CliContext context, Function<String, Boolean> supplied) {
		Path configPath = configFile(context);
		context.setConfigFile(configPath);

		// The profile has to settle first: everything else may be read from it.
		if (!supplied.apply("--profile")) {
			String fromEnv = env.apply(ENV_PROFILE);
			if (fromEnv != null && !fromEnv.isBlank()) {
				context.setProfile(fromEnv);
			}
		}

		CliConfigFile config = read(configPath);
		if (context.getProfile() == null || context.getProfile().isBlank()) {
			context.setProfile(config.getCurrentProfile());
		}
		Profile profile = config.find(context.profileName());

		if (!supplied.apply("--server")) {
			String resolved = resolveServer(profile);
			if (resolved != null) {
				context.setServerUrl(resolved);
			}
		}

		if (!supplied.apply("--output")) {
			String fromEnv = env.apply(ENV_OUTPUT);
			if (fromEnv != null && !fromEnv.isBlank()) {
				context.setOutput(OutputFormat.parse(fromEnv));
			} else if (profile != null && profile.getOutput() != null) {
				context.setOutput(OutputFormat.parse(profile.getOutput()));
			}
		}

		if (!supplied.apply("--timeout")) {
			String fromEnv = env.apply(ENV_TIMEOUT);
			if (fromEnv != null && !fromEnv.isBlank()) {
				context.setTimeout(parseDuration(fromEnv));
			} else if (profile != null && profile.getTimeout() != null) {
				context.setTimeout(parseDuration(profile.getTimeout()));
			}
		}

		if (!supplied.apply("--token") && !supplied.apply("--token-file")) {
			String fromEnv = env.apply(ENV_TOKEN);
			if (fromEnv != null && !fromEnv.isBlank()) {
				context.setToken(fromEnv);
			}
		}

		if (context.getColorMode() == null) {
			context.setColorMode(ColorMode.AUTO);
		}
	}

	/**
	 * Resolve the server from the environment or the profile.
	 *
	 * <p>{@code METALOOM_SERVER} wins; failing that the {@code LOOM_HOST}/{@code LOOM_PORT}
	 * pair used by Cortex and the server is assembled, so a developer who has already
	 * exported those does not need a third variable.</p>
	 */
	private String resolveServer(Profile profile) {
		String server = env.apply(ENV_SERVER);
		if (server != null && !server.isBlank()) {
			return server;
		}
		String host = env.apply(ENV_LOOM_HOST);
		String port = env.apply(ENV_LOOM_PORT);
		if (host != null && !host.isBlank()) {
			return "http://" + host + ":" + (port == null || port.isBlank() ? "6333" : port);
		}
		if (profile != null && profile.getServer() != null && !profile.getServer().isBlank()) {
			return profile.getServer();
		}
		return null;
	}

	/**
	 * Parse a duration.
	 *
	 * <p>Accepts the friendly forms people type ({@code 30s}, {@code 5m}, {@code 1h}) as
	 * well as ISO-8601, and a bare number as seconds.</p>
	 */
	public static Duration parseDuration(String value) {
		String text = value.trim().toLowerCase();
		try {
			if (text.startsWith("p")) {
				return Duration.parse(text);
			}
			if (text.endsWith("ms")) {
				return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
			}
			if (text.endsWith("s")) {
				return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
			}
			if (text.endsWith("m")) {
				return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
			}
			if (text.endsWith("h")) {
				return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
			}
			return Duration.ofSeconds(Long.parseLong(text));
		} catch (Exception e) {
			throw new IllegalArgumentException(
				"Not a valid duration: '" + value + "'. Try 30s, 5m, 1h or an ISO-8601 value like PT30S.", e);
		}
	}
}
