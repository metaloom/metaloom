package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
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
		assertThat(media).hasSHA512();
		assertThat(result).hasOutput(SceneDetectionNode.OUTPUT_SCENE_DETECTION);
		System.out.println("Scenes: " + result.get(SceneDetectionNode.OUTPUT_SCENE_DETECTION));
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

	/**
	 * Override to bypass LoomClientMock which fails with Java 25 Mockito restrictions.
	 */
	@Override
	public SceneDetectionNode mockNode() {
		return mockNode(null, options());
	}

	@Override
	public SceneDetectionNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		SceneDetectionOptions options = mock(SceneDetectionOptions.class);
		when(options.isEnabled()).thenReturn(true);
		return new SceneDetectionNode(client, cortexOptions, options);
	}

}
