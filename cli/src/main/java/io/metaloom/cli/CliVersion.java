package io.metaloom.cli;

import java.io.InputStream;
import java.util.Properties;

/**
 * The CLI's own version.
 *
 * <p>Read from a Maven-filtered resource rather than the jar manifest, because a GraalVM
 * native image has no jar to read a manifest from.</p>
 */
public final class CliVersion {

	private static final String RESOURCE = "/cli-version.properties";
	private static final String VERSION = load();

	private CliVersion() {
	}

	public static String version() {
		return VERSION;
	}

	private static String load() {
		try (InputStream in = CliVersion.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return "unknown";
			}
			Properties properties = new Properties();
			properties.load(in);
			return properties.getProperty("version", "unknown");
		} catch (Exception e) {
			return "unknown";
		}
	}
}
