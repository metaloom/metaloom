package io.metaloom.cortex.common.ui;

import static io.metaloom.cortex.common.ui.HeadlessUtil.HEADLESS_ENV;
import static io.metaloom.cortex.common.ui.HeadlessUtil.HEADLESS_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The viewer suppression switch. A workstation running the build has a display, so {@link GraphicsEnvironment#isHeadless()} on its own would let debug
 * viewers pop up during {@code mvn test} — the {@value HeadlessUtil#HEADLESS_PROPERTY} property is what stops that.
 */
public class HeadlessUtilTest {

	private final Map<String, String> env = new HashMap<>();

	private String previousProperty;

	@BeforeEach
	public void useTestEnv() {
		HeadlessUtil.envLookup = env::get;
		previousProperty = System.getProperty(HEADLESS_PROPERTY);
		System.clearProperty(HEADLESS_PROPERTY);
	}

	@AfterEach
	public void resetTestEnv() {
		HeadlessUtil.envLookup = System::getenv;
		if (previousProperty == null) {
			System.clearProperty(HEADLESS_PROPERTY);
		} else {
			System.setProperty(HEADLESS_PROPERTY, previousProperty);
		}
	}

	@Test
	public void testMavenProperty() {
		// This is what surefire passes for every test run.
		System.setProperty(HEADLESS_PROPERTY, "true");
		assertTrue(HeadlessUtil.isHeadless(), "The Maven property must be honoured even when a display is present");
		assertFalse(HeadlessUtil.isViewerAllowed(), "No viewer may be opened while the Maven property is set");
	}

	@Test
	public void testBarePropertyCountsAsSet() {
		// A bare -Dmetaloom.headless has an empty value.
		System.setProperty(HEADLESS_PROPERTY, "");
		assertTrue(HeadlessUtil.isHeadless(), "A valueless -Dmetaloom.headless must count as headless");
	}

	@Test
	public void testEnvVariable() {
		env.put(HEADLESS_ENV, "true");
		assertTrue(HeadlessUtil.isHeadless(), "The environment variable must be honoured even when a display is present");
	}

	@Test
	public void testNegatedFlags() {
		System.setProperty(HEADLESS_PROPERTY, "false");
		env.put(HEADLESS_ENV, "false");
		// Whatever the display situation is, an explicit "false" must not add headlessness of its own.
		assertTrue(HeadlessUtil.isHeadless() == GraphicsEnvironment.isHeadless(),
			"An explicitly negated flag must leave the decision to the graphics environment");
	}

	@Test
	public void testWithoutAnyFlag() {
		assertTrue(HeadlessUtil.isHeadless() == GraphicsEnvironment.isHeadless(), "Without a flag the graphics environment decides");
	}
}
