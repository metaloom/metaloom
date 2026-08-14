package io.metaloom.loom.graph;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.api.graph.AssetGraphIndex;
import io.metaloom.loom.api.graph.GraphEdge;
import io.metaloom.loom.api.graph.GraphNodeRef;
import io.metaloom.loom.api.graph.RelatedAsset;
import io.metaloom.loom.api.graph.RelatedAssetsQuery;
import io.metaloom.loom.api.search.IndexStatus;

/**
 * The bound implementation when no asset graph index is configured, or when the configured one could not be opened.
 *
 * <p>
 * Writes are silently discarded, because they are projections of rows Postgres already holds and dropping them costs nothing but a rebuild. Reads
 * return empty — and {@link #isAvailable()} returns false, which is the part that matters. A caller that reports "nothing is related" without checking
 * availability has turned a broken index into a wrong answer, and the routes are required to reject instead.
 * </p>
 */
public class NoopAssetGraphIndex implements AssetGraphIndex {

	public static final String PROVIDER_NAME = "none";

	@Override
	public void link(GraphEdge edge) {
		// Discarded: the link table is the system of record and a rebuild recovers this.
	}

	@Override
	public void linkAll(List<GraphEdge> edges) {
		// Discarded, as above.
	}

	@Override
	public void unlink(GraphEdge edge) {
		// Discarded, as above.
	}

	@Override
	public void remove(GraphNodeRef node) {
		// Discarded, as above.
	}

	@Override
	public List<RelatedAsset> relatedAssets(RelatedAssetsQuery query) {
		return List.of();
	}

	@Override
	public List<GraphNodeRef> neighbours(GraphNodeRef node, Set<String> types) {
		return List.of();
	}

	@Override
	public boolean contains(GraphNodeRef node) {
		return false;
	}

	@Override
	public void rebuild(Stream<GraphEdge> all) {
		// The stream is consumed and dropped so a caller holding an open database cursor is not left hanging.
		all.forEach(edge -> {
		});
	}

	@Override
	public Stream<UUID> streamIndexedAssetUuids() {
		return Stream.empty();
	}

	@Override
	public IndexStatus status() {
		return new IndexStatus()
			.setHealthy(false)
			.setDetail("no asset graph index is bound (LOOM_ASSET_GRAPH_PROVIDER=none)");
	}

	@Override
	public void commit() {
		// Nothing to flush.
	}

	@Override
	public void compact() {
		// Nothing to compact.
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public String providerName() {
		return PROVIDER_NAME;
	}
}
