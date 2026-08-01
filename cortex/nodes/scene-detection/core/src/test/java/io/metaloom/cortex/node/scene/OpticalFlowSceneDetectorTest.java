package io.metaloom.cortex.node.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.detector.SceneDetector;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.loom.test.TestEnvHelper;
import io.metaloom.loom.test.data.TestDataCollection;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;

/**
 * Runs the optical-flow scene detector over a short test video.
 *
 * <p>
 * See {@link FeatureSceneDetectorTest} for why this uses the small {@code video3} — {@code detect()}
 * is unbounded and the module shares one forked JVM.
 * </p>
 */
public class OpticalFlowSceneDetectorTest {

	static {
		Video4j.init();
	}

	@Test
	public void testDetection() throws IOException {
		SceneDetector dectector = new OpticalFlowSceneDetector();
		TestDataCollection data = TestEnvHelper.prepareTestdata("scene-detection-test");
		Path videoPath = data.video3().path();
		try (VideoFile video = Videos.open(videoPath)) {
			SceneDetectionResult result = dectector.detect(video);
			assertThat(result).as("The detector must return a result").isNotNull();
		}
	}
}
