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

	private static float[] filled(float v) {
		float[] a = new float[DIM];
		Arrays.fill(a, v);
		return a;
	}
}
