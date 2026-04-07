package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.api.media.LoomMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;

public class SHA512NodeTest extends AbstractBasicNodeTest<SHA512Node> {

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, SHA512Node nodeMock) {
		assertThat(media).hasXAttr(1).hasXAttr(SHA_512_KEY, testMedia.sha512());
	}

	@Test
	@Override
	public void testProcessAudio() throws IOException {
		super.testProcessAudio();
	}

	@Test
	@Override
	public void testDisabled() throws IOException {
		super.testDisabled();
	}

	@Override
	protected void assertDisabled(LoomMedia media, NodeResult result) {
		assertThat(media).hasXAttr(0);
	}

	@Override
	protected LoomMedia media(TestMedia media) {
		LoomMedia loomMedia = media(media.path());
		return loomMedia;
	}

	@Test
	@Override
	public void testProcessImage() throws IOException {
		super.testProcessImage();
	}

	@Test
	public void testProcessing() throws IOException {
		LoomMedia media = mediaVideo1();
		NodeResult result = node().process(media);
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(1).hasXAttr(SHA_512_KEY, sampleVideoSHA512());
	}

	@Test
	@Override
	public void testProcessVideo() throws IOException {
		super.testProcessVideo();
	}

	@Override
	public SHA512Node mockNode(LoomClient client, CortexOptions cortexOptions) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		when(options.isEnabled()).thenReturn(true);
		when(options.isSHA512()).thenReturn(true);
		return new SHA512Node(client, cortexOptions, options);
	}

	@Override
	protected void disableNode(SHA512Node nodeMock) {
		HashNodeOptions options = nodeMock.options();
		when(options.isSHA512()).thenReturn(false);
	}

}
