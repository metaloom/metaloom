package io.metaloom.loom.api;

import java.nio.file.Path;

public class LoomEnv {

	/**
	 * Name of the Loom config file.
	 */
	public static final String LOOM_CONF_FILENAME = "loom.yml";

	public static final Path LOCAL_ETC_PATH = Path.of("/etc", "metaloom", LOOM_CONF_FILENAME);

	public static final Path HOME_CONFIG_PATH = Path.of(System.getProperty("user.home"), ".config", "metaloom", LOOM_CONF_FILENAME);

	public static final Path LOCAL_CONFIG_PATH = Path.of("config", LOOM_CONF_FILENAME);
}
