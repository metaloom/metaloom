package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.node.hash.ChunkHashNode;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.MD5Node;
import io.metaloom.cortex.node.hash.SHA256Node;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.HashUtils;

/**
 * Integration test for the hash nodes ({@code md5}, {@code sha256}, {@code chunk-hash}, {@code sha512}). Each node runs on a real (unique) file against a
 * real Loom backend; the persisted hash is then read back through the REST API.
 */
public class HashNodesIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String MIME = "application/octet-stream";

	private byte[] payload(String seed) {
		return ("hash node integration payload " + seed).getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void testMD5PersistsToAssetRow() throws Exception {
		withLoom(client -> {
			UniqueAsset ua = createUniqueAsset(client, MIME, payload("md5"));
			assertThat(ua.asset().getHashes().getMD5()).as("MD5 absent before the node runs").isNull();

			MD5Node node = new MD5Node(client, cortexOptions(), new HashNodeOptions());
			NodeResult result = node.process(NodeContext.create(ua.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(ua.sha512()).sync().body();
			assertThat(reloaded.getHashes().getMD5())
				.as("MD5 computed by MD5Node must be readable via REST")
				.isEqualTo(HashUtils.computeMD5(ua.file()));
		});
	}

	@Test
	public void testSHA256PersistsToAssetRow() throws Exception {
		withLoom(client -> {
			UniqueAsset ua = createUniqueAsset(client, MIME, payload("sha256"));

			SHA256Node node = new SHA256Node(client, cortexOptions(), new HashNodeOptions());
			NodeResult result = node.process(NodeContext.create(ua.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(ua.sha512()).sync().body();
			assertThat(reloaded.getHashes().getSHA256()).isEqualTo(HashUtils.computeSHA256(ua.file()));
		});
	}

	@Test
	public void testChunkHashPersistsToAssetRow() throws Exception {
		withLoom(client -> {
			UniqueAsset ua = createUniqueAsset(client, MIME, payload("chunk"));

			ChunkHashNode node = new ChunkHashNode(client, cortexOptions(), new HashNodeOptions());
			NodeResult result = node.process(NodeContext.create(ua.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(ua.sha512()).sync().body();
			assertThat(reloaded.getHashes().getChunkHash()).isEqualTo(HashUtils.computeChunkHash(ua.file()));
		});
	}

	@Test
	public void testSHA512RecordsNodeResultLedger() throws Exception {
		withLoom(client -> {
			UniqueAsset ua = createUniqueAsset(client, MIME, payload("sha512"));

			SHA512Node node = new SHA512Node(client, cortexOptions(), new HashNodeOptions());
			NodeResult result = node.process(NodeContext.create(ua.media()));
			assertThat(result.getState()).isEqualTo(ResultState.SUCCESS);

			boolean recorded = client.listAssetNodeResults(ua.asset().getUuid()).sync().body().getData().stream()
				.map(NodeResultResponse::getNodeKind)
				.anyMatch("sha512"::equals);
			assertThat(recorded).as("sha512 node-result ledger row must be readable via REST").isTrue();
		});
	}

	@Test
	public void testMD5IsIdempotentOnRerun() throws Exception {
		withLoom(client -> {
			UniqueAsset ua = createUniqueAsset(client, MIME, payload("idempotent"));

			MD5Node node = new MD5Node(client, cortexOptions(), new HashNodeOptions());
			node.process(NodeContext.create(ua.media()));
			// A second run must not fail (the asset update is an upsert).
			NodeResult second = node.process(NodeContext.create(ua.media()));
			assertThat(second.getState()).isEqualTo(ResultState.SUCCESS);

			AssetResponse reloaded = client.loadAsset(ua.sha512()).sync().body();
			assertThat(reloaded.getHashes().getMD5()).isEqualTo(HashUtils.computeMD5(ua.file()));
		});
	}
}
