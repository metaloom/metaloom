package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.tts.TtsClient;
import io.metaloom.cortex.node.tts.TtsNode;
import io.metaloom.cortex.node.tts.TtsNodeOptions;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

/**
 * Integration test for {@code TtsNode}. The node runs its real text-in / audio-out + persistence path against a real asset, but its injected
 * {@link TtsClient} is replaced by a stub returning fixed WAV bytes instead of calling a live FastAPI sidecar. The generated WAV is written to the
 * local {@code tts_bin} cache and a {@code tts} node-result ledger row is recorded; the test reads that ledger row back through REST (ledger-only
 * persistence - the produced bytes stay local, like {@code ThumbnailNode}).
 */
public class TtsNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final byte[] FAKE_WAV = "RIFF....WAVEfake-audio-bytes".getBytes();

	/** A TtsClient that returns fixed WAV bytes instead of calling the FastAPI sidecar. */
	private static TtsClient stubClient() {
		return new TtsClient("localhost", 0) {
			@Override
			public byte[] synthesize(String text, String lang, String voice) {
				return FAKE_WAV;
			}
		};
	}

	@Test
	public void testTtsWritesAudioAndRecordsLedger() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");

			Path metaPath = Files.createTempDirectory("node-it-tts");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			TtsNode node = new TtsNode(client, options, new TtsNodeOptions(), stubClient());

			NodeContext<io.metaloom.cortex.api.media.LoomMedia> ctx = NodeContext.create(media(video1()),
				NodeInputs.builder()
					.input(TtsNode.IN_TEXT, "Guten Tag, dies ist ein Test.")
					.build());
			NodeResult result = node.process(ctx);
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			// The WAV must have been written to the local tts_bin cache.
			String outPath = result.get(TtsNode.OUT_AUDIO);
			assertThat(outPath).as("the node must emit the generated audio path").isNotNull();
			assertThat(Files.exists(Path.of(outPath))).as("the WAV must be written under metaPath/tts_bin").isTrue();
			assertThat(Files.readAllBytes(Path.of(outPath))).isEqualTo(FAKE_WAV);

			// The tts node-result ledger row must be readable via REST.
			boolean recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.map(NodeResultResponse::getNodeKind)
				.anyMatch("tts"::equals);
			assertThat(recorded).as("tts node-result ledger row must be readable via REST").isTrue();
		});
	}
}
