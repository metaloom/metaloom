package io.metaloom.cortex.node.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.detector.SceneDetector;
import io.metaloom.cortex.node.scene.impl.FeatureSceneDetector;
import io.metaloom.loom.test.TestEnvHelper;
import io.metaloom.loom.test.data.TestDataCollection;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;

/**
 * Runs the feature-based scene detector over a short test video.
 *
 * <p>
 * Uses {@code video3} (~3 MB) rather than {@code video2} (~17 MB) deliberately. {@code detect()}
 * takes no bound and walks the whole file, and surefire reuses one forked JVM for the module — on
 * the larger video the native buffers retained here were enough to push the later
 * {@code SceneDetectionNodeTest} past the OOM killer. The test previously resolved the shared media
 * and then overwrote it with a hard-coded {@code /extra/vid/3.avi}, so it never actually ran.
 * </p>
 */
public class FeatureSceneDetectorTest {

	static {
		Video4j.init();
	}

	@Test
	public void testDetection() throws IOException {
		SceneDetector dectector = new FeatureSceneDetector();
		TestDataCollection data = TestEnvHelper.prepareTestdata("scene-detection-test");
		Path videoPath = data.video3().path();
		try (VideoFile video = Videos.open(videoPath)) {
			SceneDetectionResult result = dectector.detect(video);
			assertThat(result).as("The detector must return a result").isNotNull();
		}
	}
}
