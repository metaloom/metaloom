package io.metaloom.cortex.node.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.common.ui.HeadlessUtil;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.video4j.VideoFile;

/**
 * {@link AbstractSceneDetector} attaches a Swing viewer that renders every sampled frame. It is a developer aid and must stay shut during a build: the
 * detector tests run the real detector, and on a workstation with a display {@code GraphicsEnvironment.isHeadless()} alone would happily open that window.
 * The guard is {@link HeadlessUtil}, fed by the {@code metaloom.headless} property that surefire sets for every module.
 */
public class SceneDetectorViewerTest {

	private String previousProperty;

	@BeforeEach
	public void rememberProperty() {
		previousProperty = System.getProperty(HeadlessUtil.HEADLESS_PROPERTY);
	}

	@AfterEach
	public void restoreProperty() {
		if (previousProperty == null) {
			System.clearProperty(HeadlessUtil.HEADLESS_PROPERTY);
		} else {
			System.setProperty(HeadlessUtil.HEADLESS_PROPERTY, previousProperty);
		}
	}

	@Test
	public void testNoViewerWhenSuppressed() {
		System.setProperty(HeadlessUtil.HEADLESS_PROPERTY, "true");
		assertFalse(new ProbeSceneDetector().hasViewer(), "No viewer window may be attached while viewers are suppressed");
	}

	@Test
	public void testSurefirePassesTheFlag() {
		// Guards the maven-surefire-plugin configuration in the root pom: lose it and every scene detection test run on a desktop pops up a window. Only
		// a forked Maven run can be checked - an IDE runner legitimately has no such property, and there the viewer is welcome.
		String command = System.getProperty("sun.java.command", "");
		assumeTrue(command.contains("surefire"), "Only meaningful inside a Maven surefire fork");
		assertEquals("true", System.getProperty(HeadlessUtil.HEADLESS_PROPERTY),
			"Maven must set " + HeadlessUtil.HEADLESS_PROPERTY + " for tests - check systemPropertyVariables on maven-surefire-plugin in the root pom");
	}

	/**
	 * Only the constructor is under test, so this stand-in avoids pulling OpenCV natives into the check.
	 */
	private static final class ProbeSceneDetector extends AbstractSceneDetector {

		@Override
		public SceneDetectionResult detect(VideoFile video) {
			throw new UnsupportedOperationException("Not used by this test");
		}
	}
}
