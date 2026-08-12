package io.metaloom.loom.core.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.rest.model.similarity.SimilarAssetListResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * The demo database must ship fingerprints, not just pipelines that mention fingerprinting.
 *
 * <p>
 * Without them the <code>fingerprint</code> index reports zero documents on <code>/admin/indices</code> and
 * <code>GET /assets/:uuid/similar-assets</code> answers an empty list for every demo asset — a feature that looks broken rather than unused. See
 * spec/loom/SEARCH_LUCENE.md §4.
 * </p>
 *
 * <p>
 * This drives {@link DemoDatabaseInitializer#seedFingerprintComps} directly rather than {@link DemoDatabaseInitializer#init()}: the initializer only
 * populates an <em>empty</em> asset table, and the pooled test database is pre-populated, so a boot-time run would skip every seed step and assert
 * nothing.
 * </p>
 *
 * <p>
 * The seeded rows are written through the DAO, so no REST write hook runs and nothing reaches Lucene at seed time — which is the behaviour under test:
 * <code>asset_fingerprint_comp</code> is the system-of-record and an operator who switches similarity on rebuilds the index from it.
 * </p>
 */
public class DemoFingerprintSeedTest extends AbstractEndpointTest {

	/**
	 * One index directory per test: a Lucene {@code IndexWriter} holds an exclusive {@code write.lock} and every test method boots its own server.
	 */
	@TempDir
	Path indexDir;

	{
		loom.withOptions(o -> o.getSimilarity()
			.setEnabled(true)
			.setIndexPath(indexDir.resolve("index").toString()));
	}

	private static final String ALGO = SimilarityOptions.DEFAULT_ALGORITHM;

	/** Four demo videos, in the order {@link DemoDatabaseInitializer#seedFingerprintComps} takes them. */
	private Asset original;
	private Asset nearDuplicate;
	private Asset meeting;
	private Asset cut;

	private List<AssetFingerprintComp> seed() {
		original = seedAsset("city-traffic.mp4");
		nearDuplicate = seedAsset("city-traffic-720p.mp4");
		meeting = seedAsset("team-meeting.mp4");
		cut = seedAsset("team-meeting-cut.mp4");
		return DemoDatabaseInitializer.seedFingerprintComps(daos().assetComponentDao(), adminUuid(),
			original, nearDuplicate, meeting, cut);
	}

	private Asset seedAsset(String filename) {
		Asset asset = daos().assetDao().createAsset(adminUuid(), SHA512.fromString(randomSha512()),
			"video/mp4", filename, "/demo/videos/" + filename, 52_000_000L);
		daos().assetDao().store(asset);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	/**
	 * The rows themselves: identity, algorithm and the sector the index actually reads.
	 */
	@Test
	public void testSeedWritesFingerprintCompsForTheDefaultAlgorithm() {
		List<AssetFingerprintComp> comps = seed();
		assertEquals(4, comps.size(), "One component per seeded demo video");

		// Scoped to the seeded assets: the pooled database is shared and pre-populated, so an absolute
		// count over the algorithm would assert somebody else's fixtures.
		Set<UUID> seeded = Set.of(original.getUuid(), nearDuplicate.getUuid(), meeting.getUuid(), cut.getUuid());
		List<AssetFingerprintComp> found = daos().assetComponentDao().findByAlgorithm(ALGO).stream()
			.filter(comp -> seeded.contains(comp.getAssetUuid()))
			.collect(Collectors.toList());
		assertEquals(4, found.size(), "Every seeded component must be found under the default algorithm");
		assertTrue(found.size() >= 2, "The similarity demo needs at least a pair to rank");

		for (AssetFingerprintComp comp : found) {
			assertEquals("fingerprint", comp.getNodeKind(), "The node kind FingerprintNode writes under");
			// The write hook and the query both use sector 0; a component on any other sector is invisible to both.
			assertEquals(0, comp.getSectorIndex());
			assertNotNull(comp.getFingerprint());
		}
	}

	/**
	 * The pair must be a <em>near</em>-duplicate: same header, one differing byte of bit data.
	 *
	 * <p>
	 * Identical hex would demo a lookup rather than a k-NN search, and a pair too far apart is dropped by the score floor and demos nothing at all.
	 * </p>
	 */
	@Test
	public void testTheSeededPairDiffersInASingleByte() {
		List<AssetFingerprintComp> comps = seed();
		String originalHex = comps.get(0).getFingerprint();
		String nearHex = comps.get(1).getFingerprint();

		// 6 header bytes (version, pad, vector size, pad) plus 32 bytes of bit data, hex encoded.
		assertEquals((6 + 32) * 2, originalHex.length());
		assertEquals("000200010000", originalHex.substring(0, 12), "Version 2, 256 component vector");
		assertEquals(originalHex.substring(0, 12), nearHex.substring(0, 12), "Both must decode under the same codec");

		long differingBytes = 0;
		for (int i = 12; i < originalHex.length(); i += 2) {
			if (!originalHex.regionMatches(i, nearHex, i, 2)) {
				differingBytes++;
			}
		}
		assertEquals(1, differingBytes, "The re-encode differs from its original in exactly one byte of bit data");
	}

	/**
	 * The whole point of the fixture: a similarity query over the seeded pair returns the partner.
	 *
	 * <p>
	 * The rebuild in the middle is not incidental. The seed writes components and nothing else, so the index is empty until it is built from the
	 * component table — exactly what an operator does after switching <code>LOOM_SIMILARITY_ENABLED</code> on.
	 * </p>
	 */
	@Test
	public void testSimilarityQueryOverTheSeededPairReturnsThePartner() throws LoomClientException {
		seed();

		LoomHttpClient client = httpClient();
		loginAdmin(client);
		client.rebuildSimilarityIndex().sync();

		SimilarAssetListResponse response = client.listSimilarAssets(original.getUuid(), ALGO, 10, 0.10f).sync().body();
		assertNotNull(response.getData());

		List<String> hits = response.getData().stream().map(SimilarAssetResponse::getAssetUuid).collect(Collectors.toList());
		assertTrue(hits.contains(nearDuplicate.getUuid().toString()),
			"The re-encode of the same footage must be reported as a near-duplicate, got " + hits);
		assertTrue(!hits.contains(original.getUuid().toString()), "The query asset must be excluded from its own result");
		// The unrelated videos are seeded at least 64 of 256 bits away, which scores below the 0.10 floor.
		assertTrue(!hits.contains(meeting.getUuid().toString()) && !hits.contains(cut.getUuid().toString()),
			"An unrelated demo video must not be proposed as a duplicate, got " + hits);

		SimilarAssetResponse hit = response.getData().stream()
			.filter(h -> nearDuplicate.getUuid().toString().equals(h.getAssetUuid()))
			.findFirst().orElseThrow();
		// 1 / (1 + hamming) over 0/1 components: one differing bit is 0.5, which is also the score the
		// seeded dedup proposal over these two assets records.
		assertEquals(0.5f, hit.getScore(), 0.001f);
		// A dedup consumer identifies the duplicate by content hash, not by uuid.
		assertEquals(nearDuplicate.getSHA512().toString(), hit.getSha512());
	}
}
