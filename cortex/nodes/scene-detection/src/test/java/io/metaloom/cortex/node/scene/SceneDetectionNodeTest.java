package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.api.media.LoomMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.scene.SceneDetectionMedia.SCENE_DETECTION;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.scene.SceneDetectionMedia;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;
import io.metaloom.video4j.Video4j;

public class SceneDetectionNodeTest extends AbstractBasicNodeTest<SceneDetectionNode> {

	static {
		Video4j.init();
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, SceneDetectionNode nodeMock) {
		SceneDetectionMedia sceneMedia = media.of(SCENE_DETECTION);
		assertThat(media).hasXAttr(1).hasXAttr(SHA_512_KEY, testMedia.sha512());
		// assertThat(media).hasXAttr(SceneDetectionMedia.SCENE_DETECTION_FLAG_KEY);

		SceneDetectionResult detection = sceneMedia.getSceneDetection();
		System.out.println("Scenes: " + detection.scenes());
	}

	@Override
	protected void assertProcessedImage(SceneDetectionNode nodeMock, LoomMedia media, TestMedia image) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void assertProcessedDoc(SceneDetectionNode nodeMock, LoomMedia media, TestMedia docMedia) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void assertProcessedAudio(SceneDetectionNode nodeMock, LoomMedia media, TestMedia audio) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void disableNode(SceneDetectionNode nodeMock) {
		SceneDetectionOptions options = nodeMock.options();
		when(options.isEnabled()).thenReturn(false);
	}

	@Override
	public SceneDetectionNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		SceneDetectionOptions options = mock(SceneDetectionOptions.class);
		when(options.isEnabled()).thenReturn(true);
		return new SceneDetectionNode(client, cortexOptions, options);
	}

}
