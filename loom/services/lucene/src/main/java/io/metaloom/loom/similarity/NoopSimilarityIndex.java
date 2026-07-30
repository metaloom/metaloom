package io.metaloom.loom.similarity;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.api.search.IndexedFingerprint;
import io.metaloom.loom.api.search.SimilarityHit;
import io.metaloom.loom.api.search.SimilarityIndex;

/**
 * The {@link SimilarityIndex} bound when fingerprint similarity is disabled ({@code LOOM_SIMILARITY_ENABLED=false}) or the index directory is not
 * usable.
 *
 * <p>
 * Similarity is a capability, not a dependency: a missing index must never stop Loom from booting or break unrelated routes. Every mutation is a
 * no-op and {@link #query} returns an empty list. {@link #isAvailable()} returns {@code false} so the endpoint can <b>reject</b> queries with a clear
 * reason rather than silently returning "no duplicates" (see LUCENE_PLAN.md §5).
 * </p>
 */
public class NoopSimilarityIndex implements SimilarityIndex {

	@Override
	public void index(UUID assetUuid, String sha512, String algorithm, float[] vector) {
		// no-op
	}

	@Override
	public void remove(UUID assetUuid) {
		// no-op
	}

	@Override
	public List<SimilarityHit> query(String algorithm, float[] vector, int limit, float scoreThreshold) {
		return List.of();
	}

	@Override
	public void rebuild(Stream<IndexedFingerprint> all) {
		// Drain the stream so callers that build it lazily do not leak resources.
		if (all != null) {
			all.close();
		}
	}

	@Override
	public void commit() {
		// no-op
	}

	@Override
	public boolean isAvailable() {
		return false;
	}
}
