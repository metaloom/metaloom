package io.metaloom.loom.api;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public interface LoomVersion {

	public static final String PREFIX = "loom";
	public static final String VERSION = "1_0";

	static AtomicReference<BuildInfo> buildInfo = new AtomicReference<BuildInfo>(null);

	/**
	 * Return the loom build information.
	 * 
	 * @return Loom version and build timestamp.
	 */
	static BuildInfo getBuildInfo() {
		try {
			if (buildInfo.get() == null) {
				Properties buildProperties = new Properties();
				buildProperties.load(LoomVersion.class.getResourceAsStream("/loom.build.properties"));
				// Cache the build information
				buildInfo.set(new BuildInfo(buildProperties));
			}
			return buildInfo.get();
		} catch (Exception e) {
			return new BuildInfo("unknown", "unknown");
		}
	}

	/**
	 * Return the loom version (without build timestamp)
	 *
	 * @return Loom version
	 */
	static String getPlainVersion() {
		return getBuildInfo().getVersion();
	}
}
