package io.metaloom.loom.test.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.media.impl.LoomMediaImpl;
import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.node.hash.MD5Node;
import io.metaloom.cortex.node.hash.SHA512Node;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.loom.api.Loom;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Integration test that verifies the pipeline persistence path:
 * node {@code compute()} → Loom REST → database.
 *
 * <p>Flow:
 * <ol>
 * <li>Start Loom server in-process (via {@link LoomProviderExtension}).</li>
 * <li>Compute the SHA-512 of a fresh temp file locally.</li>
 * <li>Pre-create an asset in Loom with that SHA-512 (MD5 left null).</li>
 * <li>Build a pipeline: {@code sha512 → md5} using the
 * production {@link CortexNodeAdapter}.</li>
 * <li>Execute the pipeline against the file.</li>
 * <li>Reload the asset from Loom and assert the MD5 hash was persisted.</li>
 * </ol>
 *
 * <p>
 * Nothing flushes and no sink node takes part: each node persists its own result inside
 * {@code compute()}. This test used to end in a {@code LoomNode} that re-sent hashes the producers
 * had already written; that node was removed once per-node persistence made it redundant. What is
 * asserted here is unchanged — the hash a node computed is readable from the database afterwards —
 * only the mechanism that gets it there is now the node itself.
 * </p>
 */
public class PipelinePersistenceIntegrationTest extends AbstractIntegrationTest {

	@Test
	public void testPipelinePersistsResultsToLoom() throws Exception {
		Loom server = loomServer();
		server.run(false);
		try (LoomHttpClient client = httpClient(server)) {
			loginAdmin(client);

			// 1. Prepare a temp file and compute its SHA-512
			File tempFile = File.createTempFile("pipeline-persist-", ".bin");
			tempFile.deleteOnExit();
			Files.write(tempFile.toPath(), "pipeline persistence integration test payload".getBytes());
			SHA512 sha512 = HashUtils.computeSHA512(tempFile);

			// 2. Pre-create the asset in Loom (MD5 left null, will be filled by the pipeline)
			AssetCreateRequest createReq = new AssetCreateRequest();
			createReq.setFile(new FileInfo()
				.setFilename(tempFile.getName())
				.setMimeType("application/octet-stream")
				.setOrigin(tempFile.getAbsolutePath())
				.setSize(tempFile.length()));
			createReq.setHashes(new HashInfo().setSHA512(sha512));
			AssetResponse created = client.createAsset(createReq).sync().body();
			assertThat(created.getHashes().getMD5()).as("MD5 must be absent before pipeline runs").isNull();

			// 3. Build the pipeline with real nodes and a real Loom client
			CortexOptions cortexOptions = new CortexOptions();
			LoomMedia media = new LoomMediaImpl(tempFile.toPath());

			SHA512Node sha512Node = new SHA512Node(client, cortexOptions, new HashNodeOptions());
			MD5Node md5Node = new MD5Node(client, cortexOptions, new HashNodeOptions());

			CortexNodeAdapter sha512Adapter = new CortexNodeAdapter(sha512Node, NodeMode.PARALLEL, true, 1);
			CortexNodeAdapter md5Adapter = new CortexNodeAdapter(md5Node, NodeMode.PARALLEL, true, 1);

			// 4. Run the chain.
			//
			// The graph is evaluated on Loom now, so this test no longer builds a
			// Pipeline or drives an in-Cortex executor. It runs the nodes in the order Loom would
			// dispatch them. SHA-512 has to go first: it is what stamps the content identity onto the
			// media handle, and that is how the MD5 node finds the asset row it writes to.
			NodeResult sha512Result = sha512Adapter.process(media, NodeInputs.empty());
			assertThat(sha512Result.getState()).as("sha512 should not fail").isNotEqualTo(ResultState.FAILED);

			NodeResult md5Result = md5Adapter.process(media, NodeInputs.empty());
			assertThat(md5Result.getState()).as("md5 should not fail").isNotEqualTo(ResultState.FAILED);

			// 5. Reload the asset from Loom and assert MD5 was persisted
			AssetResponse reloaded = client.loadAsset(sha512).sync().body();
			assertThat(reloaded).isNotNull();
			assertThat(reloaded.getHashes().getSHA512()).isEqualTo(sha512);
			assertThat(reloaded.getHashes().getMD5())
				.as("MD5 computed by MD5Node should be persisted by the node itself")
				.isNotNull();
			assertThat(reloaded.getHashes().getMD5()).isEqualTo(HashUtils.computeMD5(tempFile));
		} finally {
			server.shutdown();
		}
	}

}
