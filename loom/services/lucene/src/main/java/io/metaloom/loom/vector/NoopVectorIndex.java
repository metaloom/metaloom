package io.metaloom.loom.vector;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.api.search.IndexStatus;
import io.metaloom.loom.api.search.VectorHit;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorQuery;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;

/**
 * The {@link VectorIndex} bound when no backend is selected ({@code LOOM_VECTOR_INDEX_PROVIDER=none}) or the chosen one could not be opened.
 *
 * <p>
 * A vector index is a capability, not a dependency: without it embeddings are still produced and still stored in Postgres, and every other route keeps
 * working - only similarity queries are unavailable. Rows written while this is bound stay {@code dirty}, so switching a real backend on and running a
 * rebuild picks up everything written in the meantime; nothing is lost by running without one.
 * </p>
 *
 * <p>
 * Every mutation is a no-op and {@link #query} returns an empty list, but {@link #isAvailable()} returns {@code false} so the query routes can
 * <b>reject</b> with a named reason. Returning an empty result instead would make "there is no index" indistinguishable from "this face matches
 * nobody", which is the one wrong answer this class exists to avoid.
 * </p>
 */
public class NoopVectorIndex implements VectorIndex {

	@Override
	public void index(VectorRecord record) {
		// no-op
	}

	@Override
	public void indexAll(List<VectorRecord> records) {
		// no-op
	}

	@Override
	public void removeByEmbedding(UUID embeddingUuid) {
		// no-op
	}

	@Override
	public void removeByAsset(UUID assetUuid) {
		// no-op
	}

	@Override
	public List<VectorHit> query(VectorQuery query) {
		return List.of();
	}

	@Override
	public void rebuild(Stream<VectorRecord> all) {
		// Drain the stream so callers that build it lazily do not leak a database cursor.
		if (all != null) {
			all.close();
		}
	}

	@Override
	public void drop(VectorSpace space) {
		// no-op
	}

	@Override
	public IndexStatus status() {
		return new IndexStatus().setHealthy(false).setDetail("No vector index backend is bound (LOOM_VECTOR_INDEX_PROVIDER=none).");
	}

	@Override
	public IndexStatus status(VectorSpace space) {
		return status();
	}

	@Override
	public Stream<UUID> streamIndexedEmbeddingUuids() {
		return Stream.empty();
	}

	@Override
	public void commit() {
		// no-op
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public String providerName() {
		return "none";
	}
}
