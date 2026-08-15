package io.metaloom.cortex.common.ui;

import java.awt.GraphicsEnvironment;
import java.util.function.Function;

/**
 * Single answer to "may this process open a window?".
 *
 * <p>
 * Debug helpers such as {@code io.metaloom.video4j.utils.SimpleImageViewer} open a Swing frame. That is what a developer wants when running a detector by
 * hand, and a nuisance everywhere else: a Maven build on a workstation <em>does</em> have a display, so {@link GraphicsEnvironment#isHeadless()} alone
 * answers {@code false} and the test suite starts throwing viewer windows onto the screen. Maven therefore sets {@value #HEADLESS_PROPERTY} for every
 * surefire run (configured on {@code maven-surefire-plugin} in the root {@code pom.xml}), and this helper treats that as headless.
 *
 * <p>
 * A process is considered headless when any of the following holds:
 * <ol>
 * <li>{@link GraphicsEnvironment#isHeadless()} — there is no display at all (CI, container, server).</li>
 * <li>The system property {@value #HEADLESS_PROPERTY} is set — Maven sets it for tests; it can also be passed by hand via
 * {@code -Dmetaloom.headless}. A bare {@code -Dmetaloom.headless} (empty value) counts as {@code true}.</li>
 * <li>The environment variable {@value #HEADLESS_ENV} is set to a truthy value — the same switch for shell and container runs.</li>
 * </ol>
 *
 * <p>
 * Only when none of them holds may a viewer be created. Never call {@code GraphicsEnvironment.isHeadless()} directly to guard a viewer — it does not know
 * about the Maven flag.
 */
public final class HeadlessUtil {

	/** System property that suppresses interactive viewers. Set by Maven for every test run. */
	public static final String HEADLESS_PROPERTY = "metaloom.headless";

	/** Environment variable counterpart of {@value #HEADLESS_PROPERTY}. */
	public static final String HEADLESS_ENV = "METALOOM_HEADLESS";

	/** Swapped by tests, following the same convention as {@code CortexEnvOptions#envLookup}. */
	static Function<String, String> envLookup = System::getenv;

	private HeadlessUtil() {
	}

	/**
	 * Check whether interactive windows are off-limits for this process.
	 *
	 * @return true when no viewer/frame must be opened
	 */
	public static boolean isHeadless() {
		if (GraphicsEnvironment.isHeadless()) {
			return true;
		}
		if (isEnabled(System.getProperty(HEADLESS_PROPERTY))) {
			return true;
		}
		return isEnabled(envLookup.apply(HEADLESS_ENV));
	}

	/**
	 * Inverse of {@link #isHeadless()} — reads better at the call site that creates a viewer.
	 *
	 * @return true when a viewer window may be opened
	 */
	public static boolean isViewerAllowed() {
		return !isHeadless();
	}

	/**
	 * A flag counts as set when it is present and not explicitly negated. An empty value is "set" so that a bare {@code -Dmetaloom.headless} works.
	 *
	 * @param value
	 *            raw property / environment value, may be {@code null}
	 * @return true when the flag is on
	 */
	private static boolean isEnabled(String value) {
		if (value == null) {
			return false;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() || Boolean.parseBoolean(trimmed);
	}
}
