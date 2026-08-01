package io.metaloom.cortex.node.hash;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.test.AbstractBasicNodeTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.test.data.TestMedia;

public class SHA256NodeTest extends AbstractBasicNodeTest<SHA256Node> {

	@Test
	public void testProcessing() throws IOException {
		LoomMedia media = mediaVideo1();
		NodeResult result = node().process(ctx(media));
		assertThat(result).isSuccess()
			.hasOutput(SHA256Node.OUT_HASH, sampleVideoSHA256().toString());
	}

	@Test
	@Override
	public void testDisabled() throws IOException {
		super.testDisabled();
	}

	@Test
	@Override
	public void testProcessDoc() throws IOException {
		super.testProcessDoc();
	}

	/**
	 * An asset that already carries a SHA-256 in Loom must be reused rather than rehashed
	 * — re-reading the file is the expensive half of this node.
	 *
	 * <p>The origin assertion is what makes this a test of the remote path: computing the
	 * hash locally yields the same value, so asserting on the output alone would pass
	 * either way. It did, for as long as the asset mock returned a null {@code HashInfo}
	 * and the node quietly computed instead.</p>
	 */
	@Test
	public void testPullFromLoom() throws LoomClientException {
		// An asset Loom already knows the SHA-256 of.
		AssetResponse response = mock(AssetResponse.class);
		when(response.getHashes()).thenReturn(new HashInfo().setSHA256(sampleVideoSHA256()));
		LoomClient clientMock = mockClient(response);
		CortexOptions cortexOptions = null;

		LoomMedia media = mediaVideo1();
		NodeContext ctx = ctx(media);
		NodeResult result = mockNode(clientMock, cortexOptions).process(ctx);

		assertThat(result).isSuccess()
			.hasOutput(SHA256Node.OUT_HASH, sampleVideoSHA256().toString());
		assertEquals(ResultOrigin.REMOTE, ctx.resultOrigin(),
			"The hash was on the asset, so it must have come from Loom rather than being recomputed");

		// Verify that the asset was looked up - by SHA-512, which is how a node addresses
		// an asset it has only a file for. Verified against the concrete hash rather than
		// a matcher: which value it looked up by is the part worth pinning.
		verify(clientMock, times(1)).loadAsset(media.getSHA512());
	}

	/**
	 * An asset Loom knows but has never had a hash written to reports a null
	 * {@code HashInfo}. That is every asset before its first hash node run, and it must
	 * compute rather than fail.
	 */
	@Test
	public void testMissingHashInfoOnAssetComputesInstead() throws LoomClientException {
		AssetResponse response = mock(AssetResponse.class);
		when(response.getHashes()).thenReturn(null);
		LoomClient clientMock = mockClient(response);

		LoomMedia media = mediaVideo1();
		NodeContext ctx = ctx(media);
		NodeResult result = mockNode(clientMock, null).process(ctx);

		assertThat(result).isSuccess()
			.hasOutput(SHA256Node.OUT_HASH, sampleVideoSHA256().toString());
		assertEquals(ResultOrigin.COMPUTED, ctx.resultOrigin());
	}

	@Override
	public SHA256Node mockNode(LoomClient client, CortexOptions cortexOptions) {
		HashNodeOptions options = mock(HashNodeOptions.class);
		// Unstubbed, isEnabled() answers false and process() short-circuits to SKIPPED.
		when(options.isEnabled()).thenReturn(true);
		when(options.isSHA256()).thenReturn(true);
		return new SHA256Node(client, cortexOptions, options);
	}

	@Override
	protected void assertProcessed(TestMedia testMedia, LoomMedia media, NodeResult result, SHA256Node nodeMock) {
		assertThat(result).hasOutput(SHA256Node.OUT_HASH, testMedia.sha256().toString());
	}

	@Override
	protected void disableNode(SHA256Node nodeMock) {
		HashNodeOptions options = nodeMock.options();
		when(options.isSHA256()).thenReturn(false);
	}

}
