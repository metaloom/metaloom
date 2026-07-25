package io.metaloom.cortex.node.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.media.scene.Scene;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;

/**
 * Env-gated verification that {@link AbstractSceneDetector} now records real scene boundaries (not just a single whole-video scene). Runs the real
 * optical-flow detector against a video supplied via {@code SCENE_TEST_VIDEO}; when {@code SCENE_MIN_SCENES} is set, asserts at least that many scenes were
 * found. Self-skips when no video is supplied, so it is inert in ordinary CI.
 */
public class SceneBoundaryIT {

	@Test
	public void detectsMultipleScenes() {
		String path = System.getenv("SCENE_TEST_VIDEO");
		assumeTrue(path != null && !path.isBlank() && new File(path).exists(), "Set SCENE_TEST_VIDEO to an existing clip to run this verification");

		Video4j.init();
		SceneDetectionResult result;
		try (VideoFile video = Videos.open(path)) {
			result = new OpticalFlowSceneDetector().detect(video);
		}

		System.out.println("[scene-boundary] " + new File(path).getName() + " -> " + result.scenes().size() + " scene(s):");
		int i = 0;
		for (Scene s : result.scenes()) {
			System.out.printf("  scene %d: frames %d-%d (len %d)%n", i++, s.getFrom(), s.getTo(), s.length());
			assertThat(s.getTo()).as("scene end after start").isGreaterThan(s.getFrom());
		}

		assertThat(result.scenes()).as("at least one scene").isNotEmpty();
		String min = System.getenv("SCENE_MIN_SCENES");
		if (min != null && !min.isBlank()) {
			assertThat(result.scenes().size()).as("expected at least SCENE_MIN_SCENES scenes").isGreaterThanOrEqualTo(Integer.parseInt(min.trim()));
		}
	}
}
