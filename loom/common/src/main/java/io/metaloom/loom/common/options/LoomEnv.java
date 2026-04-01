package io.metaloom.loom.common.options;

public final class LoomEnv {

	private static final String LOOM_INITIAL_PASSWORD_ENV = "LOOM_INITIAL_PASSWORD";

	private LoomEnv() {
	}

	public static String initialPassword() {
		return System.getenv(LOOM_INITIAL_PASSWORD_ENV);
	}

}
