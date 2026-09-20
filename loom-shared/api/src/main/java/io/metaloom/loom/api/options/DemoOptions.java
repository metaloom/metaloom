package io.metaloom.loom.api.options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Options which control where {@code DemoDatabaseInitializer} finds the media it seeds the demo database with.
 *
 * <p>
 * <b>{@link #isEnabled()} is the switch, and it is off.</b> The seed used to run on every installation, with only the presence of
 * {@code demo-content/} separating the demo container from a plain server — and that separated nothing, because the initializer paints its own
 * images when the directory is missing. So a production server given an empty database silently invented a space, three libraries, two
 * collections, a cast of people and a wall of assets, and the operator's first real library arrived beside them with nothing to tell the two
 * apart.
 * </p>
 *
 * <p>
 * The demo image therefore sets {@code LOOM_DEMO_ENABLED=true} explicitly, next to the content directory it already set; nothing else does, so
 * nothing else seeds. {@link #getContentDirectory()} still decides <em>what</em> gets seeded when it is on — the shipped media, or the images the
 * initializer paints for itself when the directory is absent.
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

	/**
	 * Whether to seed the demo database at all.
	 *
	 * <p>
	 * Off, so that "is this a demo?" has exactly one answer and somebody has to say yes to it. The seed is still guarded by an empty-asset check
	 * on top of this, so turning it on for an installation that already holds assets does nothing.
	 * </p>
	 */
	@EnvironmentVariable(name = "LOOM_DEMO_ENABLED", description = "Seed the demo space, libraries, people, assets and pipelines when the database holds no assets yet. Off by default: only the loom-demo image turns it on. A server with this off never writes demo content, whatever demo media it can see.")
	private boolean enabled = false;

	public boolean isEnabled() {
		return enabled;
	}

	public DemoOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

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
