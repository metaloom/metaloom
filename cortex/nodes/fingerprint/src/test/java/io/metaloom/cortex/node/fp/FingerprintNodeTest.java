package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.media.fingerprint.FingerprintMedia.FINGERPRINT_KEY;
import static io.metaloom.cortex.media.hash.HashMedia.SHA_512_KEY;
import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.test.data.TestMedia;

public class FingerprintNodeTest extends AbstractBasicNodeTest<FingerprintNode> {

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, FingerprintNode nodeMock) {
		assertThat(result).isSuccess();
		assertThat(media).hasXAttr(2).hasXAttr(SHA_512_KEY).hasXAttr(FINGERPRINT_KEY, data.sampleVideoFingerprint());
	}

	@Override
	protected void assertProcessedDoc(FingerprintNode nodeMock, LoomMedia media, TestMedia doc) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void assertProcessedAudio(FingerprintNode nodeMock, LoomMedia media, TestMedia audio) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void assertProcessedImage(FingerprintNode nodeMock, LoomMedia media, TestMedia image) throws IOException {
		assertSkipped(nodeMock, media);
	}

	@Override
	protected void disableNode(FingerprintNode nodeMock) {
		FingerprintNodeOptions options = nodeMock.options();
		when(options.isEnabled()).thenReturn(false);
	}

	@Override
	public FingerprintNode mockNode(LoomClient client, CortexOptions cortexOptions) {
		FingerprintNodeOptions options = mock(FingerprintNodeOptions.class);
		when(options.isEnabled()).thenReturn(true);
		return new FingerprintNode(client, cortexOptions, options);
	}
}
