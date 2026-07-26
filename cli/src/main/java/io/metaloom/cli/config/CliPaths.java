package io.metaloom.cli.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * Where the CLI keeps its files.
 *
 * <p>Honours {@code $XDG_CONFIG_HOME} and falls back to {@code ~/.config}. The environment
 * lookup is injected rather than read from {@code System.getenv} directly, so tests can
 * drive the whole resolution without touching the developer's real config.</p>
 */
public class CliPaths {

	public static final String CONFIG_FILE = "cli.yml";
	public static final String CREDENTIALS_FILE = "credentials.yml";

	private final Path configDir;

	public CliPaths(Function<String, String> env, String userHome) {
		String xdg = env.apply("XDG_CONFIG_HOME");
		Path base = xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(userHome, ".config");
		this.configDir = base.resolve("metaloom");
	}

	public static CliPaths fromEnvironment() {
		return new CliPaths(System::getenv, System.getProperty("user.home", "."));
	}

	public Path configDir() {
		return configDir;
	}

	public Path configFile() {
		return configDir.resolve(CONFIG_FILE);
	}

	public Path credentialsFile() {
		return configDir.resolve(CREDENTIALS_FILE);
	}
}
