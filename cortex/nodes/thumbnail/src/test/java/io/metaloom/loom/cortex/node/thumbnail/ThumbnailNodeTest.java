package io.metaloom.loom.cortex.node.thumbnail;

import static io.metaloom.cortex.api.media.LoomMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static io.metaloom.cortex.media.thumbnail.ThumbnailMedia.THUMBNAIL_FLAG_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.thumbnail.ThumbnailNode;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.cortex.common.node.media.LoomClientMock;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;

public class ThumbnailNodeTest extends AbstractMediaTest {

	private File thumbnailDir;

	@BeforeEach
	public void setup() throws IOException {
		thumbnailDir = new File("target/test-output");
		if (thumbnailDir.exists()) {
			FileUtils.deleteDirectory(thumbnailDir);
		}
		assertTrue(thumbnailDir.mkdirs(), "Unable to create thumbnail directory.");
	}

	@Test
	public void testAction() throws IOException, LoomClientException {
		ThumbnailNode node = mockNode();
		LoomMedia media = mediaVideo3();
		NodeResult result = node.process(ctx(media));
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(2).hasXAttr(SHA_512_KEY).hasXAttr(THUMBNAIL_FLAG_KEY);
		assertThat(new File(thumbnailDir, media.getSHA512() + ".jpg")).exists();
	}

	public ThumbnailNode mockNode() throws LoomClientException {
		LoomClient client = LoomClientMock.mockClient();

		ThumbnailNodeOptions options = new ThumbnailNodeOptions();
		return new ThumbnailNode(client, new CortexOptions(), options);
	}
}
