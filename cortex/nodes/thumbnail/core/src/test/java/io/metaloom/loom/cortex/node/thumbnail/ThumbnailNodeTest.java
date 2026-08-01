package io.metaloom.loom.cortex.node.thumbnail;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.thumbnail.ThumbnailNode;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.video4j.Video4j;

public class ThumbnailNodeTest extends AbstractMediaTest {

	static {
		// ThumbnailNode opens the video through video4j, which needs the OpenCV
		// natives loaded before the first VideoCapture is constructed.
		Video4j.init();
	}

	@Test
	public void testAction() throws IOException, LoomClientException {
		ThumbnailNode node = mockNode();
		LoomMedia media = mediaVideo3();
		NodeResult result = node.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(result).hasOutput(ThumbnailNode.OUT_FLAG);

		// The node stores the thumbnail under the cortex meta path (thumbnail_bin/<segments>/<sha512>.thumb)
		// and reports where it landed on the thumbnail port. Assert against that rather than
		// re-deriving the layout here — the test used to look for a ".jpg" in its own output
		// directory, which the node stopped writing when thumbnails moved into the meta path.
		String thumbnailPath = result.get(ThumbnailNode.OUT_THUMBNAIL);
		assertThat(thumbnailPath).as("The node must report where it wrote the thumbnail").isNotNull();
		assertThat(new File(thumbnailPath)).exists();
	}

	public ThumbnailNode mockNode() throws LoomClientException {
		LoomClient client = LoomClientMock.mockClient();

		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		// Must be the fixture's CortexOptions, not a fresh one: the node resolves the thumbnail
		// target under getMetaPath(), which only the fixture populates. A bare CortexOptions
		// leaves it null and the node fails inside compute().
		return new ThumbnailNode(client, options(), options);
	}
}
