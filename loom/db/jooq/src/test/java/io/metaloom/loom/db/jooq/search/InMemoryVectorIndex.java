package io.metaloom.loom.db.jooq.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.api.search.IndexStatus;
import io.metaloom.loom.api.search.VectorHit;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorQuery;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;

/**
 * An exact-search {@link VectorIndex} for tests.
 *
 * <p>
 * Brute force over a map, which for a handful of fixtures is both fast enough and <em>exact</em> - an approximate index can legitimately omit a true
 * neighbour, so asserting on ranking against one would produce a test that fails occasionally for a reason that is not a bug. Lucene's HNSW behaviour
 * is covered by {@code LuceneVectorIndexTest}; what the provider needs from this is a truthful ordering.
 * </p>
 *
 * <p>
 * 🔴 <b>Scores follow Lucene's formula, not raw cosine.</b> {@code KnnFloatVectorField} defaults to Euclidean similarity, scored
 * {@code 1 / (1 + squaredDistance)}: identical unit vectors give 1.0, orthogonal ones 1/3, opposite ones 0.2. Scoring cosine here instead would be
 * simpler and would quietly make {@code LOOM_SEARCH_VECTOR_MIN_SCORE} mean something different in tests than in production - which is the one property
 * of that option a test is worth having for.
 * </p>
 */
public class InMemoryVectorIndex implements VectorIndex {

	private final Map<UUID, VectorRecord> records = new LinkedHashMap<>();

	private boolean available = true;

	public InMemoryVectorIndex unavailable() {
		this.available = false;
		return this;
	}

	@Override
	public void index(VectorRecord record) {
		records.put(record.embeddingUuid(), record);
	}

	@Override
	public void indexAll(List<VectorRecord> batch) {
		batch.forEach(this::index);
	}

	@Override
	public void removeByEmbedding(UUID embeddingUuid) {
		records.remove(embeddingUuid);
	}

	@Override
	public void removeByAsset(UUID assetUuid) {
		records.values().removeIf(record -> assetUuid.equals(record.assetUuid()));
	}

	@Override
	public List<VectorHit> query(VectorQuery query) {
		List<VectorHit> hits = new ArrayList<>();
		for (VectorRecord record : records.values()) {
			if (!sameSpace(record.space(), query.space())) {
				continue;
			}
			if (query.excludeAssetUuid() != null && query.excludeAssetUuid().equals(record.assetUuid())) {
				continue;
			}
			float score = euclideanScore(record.vector(), query.vector());
			if (score >= query.scoreThreshold()) {
				hits.add(new VectorHit(record.embeddingUuid(), record.assetUuid(), record.detectionUuid(), score));
			}
		}
		hits.sort(Comparator.comparingDouble(VectorHit::score).reversed());
		return hits.size() > query.limit() ? new ArrayList<>(hits.subList(0, query.limit())) : hits;
	}

	@Override
	public void rebuild(Stream<VectorRecord> all) {
		records.clear();
		all.forEach(this::index);
	}

	@Override
	public void drop(VectorSpace space) {
		records.values().removeIf(record -> sameSpace(record.space(), space));
	}

	@Override
	public IndexStatus status() {
		return new IndexStatus().setHealthy(available).setDocumentCount(records.size());
	}

	@Override
	public IndexStatus status(VectorSpace space) {
		long count = records.values().stream().filter(record -> sameSpace(record.space(), space)).count();
		return new IndexStatus().setHealthy(available).setDocumentCount(count);
	}

	@Override
	public Stream<UUID> streamIndexedEmbeddingUuids() {
		return List.copyOf(records.keySet()).stream();
	}

	@Override
	public void commit() {
		// Nothing is buffered.
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public String providerName() {
		return "in-memory";
	}

	public int size() {
		return records.size();
	}

	private static boolean sameSpace(VectorSpace a, VectorSpace b) {
		return a.key().equals(b.key());
	}

	/** Lucene's {@code VectorSimilarityFunction.EUCLIDEAN} score: {@code 1 / (1 + squaredDistance)}. */
	private static float euclideanScore(float[] a, float[] b) {
		double squared = 0;
		for (int i = 0; i < a.length; i++) {
			double delta = (double) a[i] - b[i];
			squared += delta * delta;
		}
		return (float) (1.0 / (1.0 + squared));
	}
}
