package io.metaloom.loom.api.options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Options which control where {@code DemoDatabaseInitializer} finds the media it seeds the demo database with.
 *
 * <p>
 * The demo seed runs on every installation — there is no demo profile and no flag — so this directory is what separates the demo container from a
 * plain server. The demo image ships {@code demo-content/} at {@code /demo-content} and points {@link #getContentDirectory()} at it; everywhere else
 * the directory is absent and the initializer falls back to the images it paints itself and to the portraits shipped inside the jar.
 * </p>
 *
 * <p>
 * There is deliberately no "demo enabled" switch here. Adding one would be a second, contradictory answer to a question the presence of the media
 * already answers, and it would change what an existing installation seeds on its next empty-database boot.
 * </p>
 */
public class DemoOptions implements Option {

	/**
	 * Where the demo container mounts the media, and the first place a missing setting looks.
	 */
	public static final String CONTAINER_CONTENT_DIRECTORY = "/demo-content";

	/**
	 * The second place a missing setting looks: the checked-in directory, relative to a working directory at the repository root. This is what makes
	 * a server started straight out of the source tree seed the same content the container does.
	 */
	public static final String SOURCE_CONTENT_DIRECTORY = "demo-content";

	@EnvironmentVariable(name = "LOOM_DEMO_CONTENT_DIR", description = "Directory holding the media the demo database is seeded with (images/, videos/, persons/, users/). When unset, /demo-content and ./demo-content are probed in that order. When neither exists the demo images are painted at runtime instead.")
	private String contentDirectory;

	public String getContentDirectory() {
		return contentDirectory;
	}

	public DemoOptions setContentDirectory(String contentDirectory) {
		this.contentDirectory = contentDirectory;
		return this;
	}

	/**
	 * Resolve the directory the demo media is read from.
	 *
	 * <p>
	 * An explicitly configured directory is returned even when it does not exist: a set-and-wrong path is a mistake worth a warning from the caller,
	 * whereas the probe below finding nothing is the ordinary case on a server.
	 * </p>
	 *
	 * @return the configured or probed directory, or null when neither is present and the caller should fall back
	 */
	public Path resolveContentDirectory() {
		if (contentDirectory != null && !contentDirectory.isBlank()) {
			return Paths.get(contentDirectory);
		}
		for (String candidate : new String[] { CONTAINER_CONTENT_DIRECTORY, SOURCE_CONTENT_DIRECTORY }) {
			Path path = Paths.get(candidate);
			if (Files.isDirectory(path)) {
				return path;
			}
		}
		return null;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (contentDirectory != null && contentDirectory.isBlank()) {
			errors.add("contentDirectory", "The demo content directory (LOOM_DEMO_CONTENT_DIR) must not be blank. Unset it to fall back to the painted demo images.");
		}
	}

}
