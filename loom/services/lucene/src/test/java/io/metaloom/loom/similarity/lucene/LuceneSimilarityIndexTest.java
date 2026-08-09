package io.metaloom.loom.similarity.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.search.HexFingerprint;
import io.metaloom.loom.api.search.IndexedFingerprint;
import io.metaloom.loom.api.search.SimilarityHit;

public class LuceneSimilarityIndexTest {

	private static final String ALGO = "metaloom-multisector-v1";
	private static final int DIM = 256;
	private static final float THRESHOLD = 0.10f;

	private LuceneSimilarityIndex index;

	private final UUID assetNear = UUID.randomUUID();
	private final UUID assetFar = UUID.randomUUID();

	private float[] base;
	private float[] near;
	private float[] far;

	@BeforeEach
	public void setup(@TempDir Path dir) {
		index = new LuceneSimilarityIndex(dir.resolve("index"));
		base = filled(0.1f);
		near = filled(0.1f);
		near[0] = 0.1001f; // almost identical -> high k-NN score
		far = filled(0.9f); // very different -> score below threshold
	}

	@AfterEach
	public void teardown() {
		if (index != null) {
			index.close();
		}
	}

	@Test
	public void shouldReturnNearNeighbourAboveThresholdAndDropDissimilar() {
		assertThat(index.isAvailable()).isTrue();
		index.index(assetNear, "sha-near", ALGO, base);
		index.index(assetFar, "sha-far", ALGO, far);
		index.commit();

		List<SimilarityHit> hits = index.query(ALGO, near, 10, THRESHOLD);
		assertThat(hits).extracting(SimilarityHit::assetUuid).contains(assetNear).doesNotContain(assetFar);
		SimilarityHit hit = hits.stream().filter(h -> h.assetUuid().equals(assetNear)).findFirst().orElseThrow();
		assertThat(hit.sha512()).isEqualTo("sha-near");
		assertThat(hit.score()).isGreaterThan(THRESHOLD);
	}

	@Test
	public void shouldRemoveAsset() {
		index.index(assetNear, "sha-near", ALGO, base);
		index.commit();
		assertThat(index.query(ALGO, near, 10, THRESHOLD)).isNotEmpty();

		index.remove(assetNear);
		index.commit();
		assertThat(index.query(ALGO, near, 10, THRESHOLD)).isEmpty();
	}

	@Test
	public void shouldUpsertOnReindex() {
		index.index(assetNear, "sha-old", ALGO, base);
		index.index(assetNear, "sha-new", ALGO, base);
		index.commit();
		List<SimilarityHit> hits = index.query(ALGO, near, 10, THRESHOLD);
		assertThat(hits).hasSize(1);
		assertThat(hits.get(0).sha512()).isEqualTo("sha-new");
	}

	@Test
	public void shouldFilterByAlgorithm() {
		index.index(assetNear, "sha-near", "other-algo", base);
		index.commit();
		assertThat(index.query(ALGO, near, 10, THRESHOLD)).isEmpty();
		assertThat(index.query("other-algo", near, 10, THRESHOLD)).isNotEmpty();
	}

	@Test
	public void shouldRebuildFromStream() {
		index.index(assetFar, "sha-far", ALGO, far);
		index.commit();

		index.rebuild(Stream.of(
			new IndexedFingerprint(assetNear, "sha-near", ALGO, base)));

		List<SimilarityHit> hits = index.query(ALGO, near, 10, THRESHOLD);
		assertThat(hits).extracting(SimilarityHit::assetUuid).containsExactly(assetNear);
	}

	/**
	 * The hex overloads are what Loom itself calls: {@code asset_fingerprint_comp} stores hex, and only this module knows the codec.
	 */
	@Test
	public void shouldIndexAndQueryByStoredHex() {
		String hex = hexFingerprint(0xFF);
		index.index(assetNear, "sha-hex", ALGO, hex);
		index.commit();

		List<SimilarityHit> hits = index.query(ALGO, hex, 10, THRESHOLD);
		assertThat(hits).extracting(SimilarityHit::assetUuid).contains(assetNear);
	}

	@Test
	public void shouldIgnoreMalformedHexInsteadOfThrowing() {
		index.index(assetNear, "sha-bad", ALGO, "not-a-fingerprint");
		index.index(assetFar, "sha-null", ALGO, (String) null);
		index.commit();

		// Nothing was indexed, and querying with bad hex degrades to an empty list rather than an exception.
		assertThat(index.query(ALGO, "not-a-fingerprint", 10, THRESHOLD)).isEmpty();
		assertThat(index.query(ALGO, base, 10, THRESHOLD)).isEmpty();
	}

	@Test
	public void shouldRebuildFromHexStream() {
		String hex = hexFingerprint(0xFF);
		index.rebuildFromHex(Stream.of(
			new HexFingerprint(assetNear, "sha-hex", ALGO, hex),
			// A malformed entry must be skipped, not abort the whole rebuild.
			new HexFingerprint(assetFar, "sha-bad", ALGO, "zzzz")));

		assertThat(index.query(ALGO, hex, 10, THRESHOLD))
			.extracting(SimilarityHit::assetUuid).containsExactly(assetNear);
	}

	@Test
	public void shouldDropOneAlgorithmAndLeaveTheOthersIntact() {
		// Same reason drop(VectorSpace) exists on the sibling SPI: rebuild() clears everything, so
		// retiring one fingerprint algorithm needed a scoped operation of its own.
		index.index(assetNear, "sha-near", ALGO, base);
		index.index(assetFar, "sha-far", "other-algo", base);

		index.drop(ALGO);

		assertThat(index.query(ALGO, base, 10, THRESHOLD)).isEmpty();
		assertThat(index.query("other-algo", base, 10, THRESHOLD))
			.extracting(SimilarityHit::assetUuid).containsExactly(assetFar);
	}

	@Test
	public void shouldReportCountsPerAlgorithmAndBytesForTheBackend() {
		index.index(assetNear, "sha-near", ALGO, base);
		index.index(assetFar, "sha-far", "other-algo", base);
		index.commit();

		assertThat(index.status(ALGO).getDocumentCount()).isEqualTo(1);
		assertThat(index.status().getDocumentCount()).isEqualTo(2);
		assertThat(index.status().getSizeBytes()).isPositive();
		assertThat(index.providerName()).isEqualTo("lucene");
	}

	@Test
	public void shouldEnumerateIndexedAssetUuidsForTheOrphanSweep() {
		index.index(assetNear, "sha-near", ALGO, base);
		index.index(assetFar, "sha-far", ALGO, far);
		index.commit();

		try (Stream<UUID> uuids = index.streamIndexedAssetUuids()) {
			assertThat(uuids.toList()).contains(assetNear, assetFar);
		}
	}

	/**
	 * Build a valid v2 fingerprint hex: 2 bytes version, 1 pad, 2 bytes vector size (256), 1 pad, then 32 bytes of bit data.
	 */
	private static String hexFingerprint(int fillByte) {
		StringBuilder sb = new StringBuilder("0002" + "00" + "0100" + "00");
		for (int i = 0; i < DIM / 8; i++) {
			sb.append(String.format("%02x", fillByte & 0xFF));
		}
		return sb.toString();
	}

	private static float[] filled(float v) {
		float[] a = new float[DIM];
		Arrays.fill(a, v);
		return a;
	}
}
