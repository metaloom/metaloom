package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.boot.DemoDatabaseInitializer;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.loom.rest.model.searchindex.IndexJobCreateRequest;
import io.metaloom.loom.rest.model.searchindex.IndexJobResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetListResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Endpoint behaviour of {@code GET /api/v1/assets/:uuid/similar-assets} and {@code POST /api/v1/similarity-index/rebuild} with the Lucene k-NN index
 * switched on.
 *
 * <p>
 * The interesting part is the <b>write path</b>: posting a fingerprint component must make the asset findable, because the index is maintained by a hook
 * on the component write. That is what makes the derived index usable at all - see spec/loom/SEARCH_LUCENE.md §4.
 * </p>
 */
public class SimilarAssetsEndpointTest extends AbstractEndpointTest {

	/**
	 * One index directory <b>per test</b>. A Lucene {@code IndexWriter} holds an exclusive {@code write.lock} on its directory, and every test method
	 * boots a fresh server here - a shared directory would leave all but the first server unable to open the index.
	 */
	@TempDir
	Path indexDir;

	/**
	 * Configure the inherited extension rather than declaring a new one: a second {@code @RegisterExtension} field would boot a second server while
	 * {@link AbstractEndpointTest#httpClient()} kept talking to the first, un-customized one.
	 *
	 * <p>
	 * The lambda body runs at server boot, by which time the per-test {@code @TempDir} has been injected.
	 * </p>
	 */
	{
		loom.withOptions(o -> o.getSimilarity()
			.setEnabled(true)
			.setIndexPath(indexDir.resolve("index").toString()));
	}

	private static final String ALGO = "metaloom-multisector-v1";

	/**
	 * A valid v2 fingerprint hex: 2 bytes version, 1 pad, 2 bytes vector size (256), 1 pad, then 32 bytes of bit data.
	 *
	 * <p>
	 * The layout lives in {@link DemoDatabaseInitializer#demoFingerprintHex(int)}, which seeds the same shape into the demo database. One copy: a hex
	 * the video4j codec rejects is logged and skipped rather than thrown, so a drifted second copy shows up as an index that is quietly empty.
	 * </p>
	 */
	private static String hexFingerprint(int fillByte) {
		return DemoDatabaseInitializer.demoFingerprintHex(fillByte);
	}

	private Asset seedAsset(String filename) {
		Asset asset = daos().assetDao().createAsset(adminUuid(), SHA512.fromString(randomSha512()),
			"video/mp4", filename, "/media/" + filename, 2048L);
		daos().assetDao().store(asset);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	private void postFingerprint(LoomHttpClient client, Asset asset, String hex) throws LoomClientException {
		client.createAssetFingerprintComp(asset.getUuid(), new FingerprintCompCreateRequest()
			.setNodeKind("fingerprint")
			.setAlgorithm(ALGO)
			.setSectorIndex(0)
			.setFingerprint(hex)).sync();
	}

	@Test
	public void testFingerprintWriteMakesAssetsFindable() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset a = seedAsset("sim_a.mp4");
		Asset b = seedAsset("sim_b.mp4");
		String hex = hexFingerprint(0xFF);

		// Same fingerprint on both: b must show up as a near-duplicate of a.
		postFingerprint(client, a, hex);
		postFingerprint(client, b, hex);

		SimilarAssetListResponse response = client.listSimilarAssets(a.getUuid(), ALGO, 10, 0.10f).sync().body();
		assertNotNull(response.getData());
		assertTrue(response.getData().stream().anyMatch(hit -> b.getUuid().toString().equals(hit.getAssetUuid())),
			"The asset with an identical fingerprint must be reported as similar");
		// Self-exclusion: an asset is never its own duplicate, or every dedup run would propose deleting everything.
		assertTrue(response.getData().stream().noneMatch(hit -> a.getUuid().toString().equals(hit.getAssetUuid())),
			"The query asset must be excluded from its own result");
	}

	@Test
	public void testAssetWithoutFingerprintYieldsEmptyList() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset lonely = seedAsset("no_fingerprint.mp4");
		SimilarAssetListResponse response = client.listSimilarAssets(lonely.getUuid(), ALGO, 10, 0.10f).sync().body();
		// 200 with an empty list, not 404: the asset exists, it simply has not been fingerprinted.
		assertTrue(response.getData() == null || response.getData().isEmpty());
	}

	@Test
	public void testDeletingTheFingerprintRemovesItFromTheIndex() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset a = seedAsset("del_a.mp4");
		Asset b = seedAsset("del_b.mp4");
		String hex = hexFingerprint(0xFF);
		postFingerprint(client, a, hex);
		postFingerprint(client, b, hex);

		UUID compUuid = UUID.fromString(client.listAssetFingerprintComps(b.getUuid()).sync().body().getData().get(0).getUuid().toString());
		client.deleteAssetFingerprintComp(b.getUuid(), compUuid).sync();

		SimilarAssetListResponse response = client.listSimilarAssets(a.getUuid(), ALGO, 10, 0.10f).sync().body();
		assertTrue(response.getData() == null
			|| response.getData().stream().noneMatch(hit -> b.getUuid().toString().equals(hit.getAssetUuid())),
			"A deleted fingerprint component must disappear from the index");
	}

	@Test
	public void testRebuildRestoresTheIndexFromTheComponentTable() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset a = seedAsset("rebuild_a.mp4");
		Asset b = seedAsset("rebuild_b.mp4");
		String hex = hexFingerprint(0xFF);
		postFingerprint(client, a, hex);
		postFingerprint(client, b, hex);

		// A rebuild is always safe: the index is a derived cache of asset_fingerprint_comp.
		client.rebuildSimilarityIndex().sync();

		SimilarAssetListResponse response = client.listSimilarAssets(a.getUuid(), ALGO, 10, 0.10f).sync().body();
		assertTrue(response.getData().stream().anyMatch(hit -> b.getUuid().toString().equals(hit.getAssetUuid())),
			"After a rebuild the near-duplicate must still be found");
	}

	/**
	 * Every write path into the index must carry the matched asset's content hash.
	 *
	 * <p>
	 * The hash lives on {@code asset} and not on {@code asset_fingerprint_comp}, so each of the three paths has to reach for it - and each of them used
	 * to pass {@code null} instead. The dedup consumer identifies a duplicate by exactly this hash, so a hit without one is a hit it cannot act on.
	 * </p>
	 */
	@Test
	public void testHitCarriesTheSha512OfTheMatchedAsset() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset a = seedAsset("hash_a.mp4");
		Asset b = seedAsset("hash_b.mp4");
		String hex = hexFingerprint(0xFF);
		postFingerprint(client, a, hex);
		postFingerprint(client, b, hex);

		// 1. the incremental write hook on the component write
		assertEquals(b.getSHA512().toString(), hitFor(client, a, b).getSha512(),
			"The write hook must index the owning asset's sha512sum");

		// 2. the legacy in-request rebuild
		client.rebuildSimilarityIndex().sync();
		assertEquals(b.getSHA512().toString(), hitFor(client, a, b).getSha512(),
			"The rebuild must join the hash back in rather than reindexing nulls");
	}

	/**
	 * The third write path: the admin {@code REINDEX} job, which streams the component table rather than listing it.
	 */
	@Test
	public void testTheReindexJobCarriesTheSha512() throws Exception {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset a = seedAsset("job_hash_a.mp4");
		Asset b = seedAsset("job_hash_b.mp4");
		String hex = hexFingerprint(0xFF);
		postFingerprint(client, a, hex);
		postFingerprint(client, b, hex);

		IndexJobResponse job = client.createSearchIndexJob("fingerprint", new IndexJobCreateRequest().setAction("REINDEX")).sync().body();
		assertEquals("SUCCEEDED", awaitJob(client, job.getUuid()).getState(), "The reindex job must finish successfully");

		assertEquals(b.getSHA512().toString(), hitFor(client, a, b).getSha512(),
			"The reindex job must index the owning asset's sha512sum");
	}

	/**
	 * The job runs outside the request that accepted it, so its result is only observable after polling. Bounded so a stuck job fails the test instead
	 * of hanging the suite.
	 */
	private IndexJobResponse awaitJob(LoomHttpClient client, UUID jobUuid) throws Exception {
		for (int i = 0; i < 100; i++) {
			IndexJobResponse job = client.loadSearchIndexJob("fingerprint", jobUuid).sync().body();
			if (!"PENDING".equals(job.getState()) && !"RUNNING".equals(job.getState())) {
				return job;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("The reindex job did not finish within 10s");
	}

	private SimilarAssetResponse hitFor(LoomHttpClient client, Asset query, Asset expected) throws LoomClientException {
		return client.listSimilarAssets(query.getUuid(), ALGO, 10, 0.10f).sync().body().getData().stream()
			.filter(hit -> expected.getUuid().toString().equals(hit.getAssetUuid()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Asset " + expected.getUuid() + " was not reported as similar to " + query.getUuid()));
	}

	@Test
	public void testBadLimitIsRejected() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		Asset a = seedAsset("badparam.mp4");
		expect(400, "Bad Request", client.listSimilarAssets(a.getUuid(), ALGO, -5, 0.10f));
	}

	@Test
	public void testRoutesRequirePermissions() throws LoomClientException {
		Asset a = seedAsset("perm_probe.mp4");
		LoomHttpClient nobody = loginPermissionlessClient();
		expect(403, "Forbidden", nobody.listSimilarAssets(a.getUuid(), ALGO, 10, 0.10f));
		expect(403, "Forbidden", nobody.rebuildSimilarityIndex());
	}

	@Test
	public void testScoreIsReported() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		Asset a = seedAsset("score_a.mp4");
		Asset b = seedAsset("score_b.mp4");
		String hex = hexFingerprint(0xFF);
		postFingerprint(client, a, hex);
		postFingerprint(client, b, hex);

		SimilarAssetListResponse response = client.listSimilarAssets(a.getUuid(), ALGO, 10, 0.10f).sync().body();
		float score = response.getData().stream()
			.filter(hit -> b.getUuid().toString().equals(hit.getAssetUuid()))
			.findFirst().orElseThrow().getScore();
		assertTrue(score > 0.10f, "An identical fingerprint must score above the default threshold, got " + score);
		assertEquals(1, response.getData().size(), "Only the one other fingerprinted asset should match");
	}
}
