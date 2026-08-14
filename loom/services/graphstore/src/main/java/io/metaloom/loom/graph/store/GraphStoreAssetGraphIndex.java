package io.metaloom.loom.graph.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.graph.core.api.CompactionOptions;
import io.metaloom.graph.core.api.CompactionReport;
import io.metaloom.graph.core.api.Direction;
import io.metaloom.graph.core.api.Edge;
import io.metaloom.graph.core.api.GraphStore;
import io.metaloom.graph.core.api.GraphStoreOptions;
import io.metaloom.graph.core.api.Properties;
import io.metaloom.graph.core.api.StoreStats;
import io.metaloom.graph.core.api.Transaction;
import io.metaloom.graph.core.api.WalSyncMode;
import io.metaloom.loom.api.graph.AssetGraphIndex;
import io.metaloom.loom.api.graph.GraphEdge;
import io.metaloom.loom.api.graph.GraphNodeRef;
import io.metaloom.loom.api.graph.RelatedAsset;
import io.metaloom.loom.api.graph.RelatedAssetsQuery;
import io.metaloom.loom.api.search.IndexStatus;

/**
 * {@link AssetGraphIndex} over {@code io.metaloom.graph:graph-storage-ffm-poc}.
 *
 * <h2>How Loom's model maps onto the engine</h2>
 *
 * Every MetaLoom row that participates in a relationship becomes a node labelled with its kind ({@code asset}, {@code tag}, ...) and carrying its
 * {@code uuid} as an indexed property. Every link row becomes one edge. A related-assets query is then two hops: out of the asset to its tags,
 * collections, remixes and people, and back down to the other assets on each.
 *
 * <h2>Two things this class exists to absorb</h2>
 *
 * <b>Identity.</b> Loom addresses everything by {@code UUID}; the engine addresses everything by a {@code long} that decodes into a file offset. The
 * translation goes through the engine's property index, not through a map held here, and that is deliberate: the engine's compaction relocates
 * records and invalidates its own ids, so any cached mapping would be wrong exactly when compaction ran. Looking the uuid up costs an index probe per
 * call, which is the right price for never being able to hand out a stale id.
 *
 * <p>
 * <b>Writes.</b> The engine takes one writer at a time and fsyncs per commit, so a per-row write hook would serialise Loom's pipeline behind roughly
 * a thousand commits a second. Everything here batches: {@link #linkAll(List)} and {@link #rebuild(Stream)} open one transaction for the whole batch.
 * That is affordable only because this is an index — the link tables are already durable in Postgres when these methods are called, so losing the
 * last batch to a crash costs a rebuild, not data.
 * </p>
 *
 * <p>
 * Neither of those is a workaround for a defect. They are what it means to use a single-writer embedded engine as a derived index, which is the only
 * role {@code spec/reports/PHASE_STATUS.md} in that project recommends for it.
 * </p>
 */
public class GraphStoreAssetGraphIndex implements AssetGraphIndex {

	private static final Logger log = LoggerFactory.getLogger(GraphStoreAssetGraphIndex.class);

	public static final String PROVIDER_NAME = "graphstore";

	/** The property every node carries, and the one the index is addressed by. */
	private static final String PROP_UUID = "uuid";

	private final Path path;
	private GraphStore store;
	private volatile boolean available;

	public GraphStoreAssetGraphIndex(Path path) {
		this.path = path;
		try {
			// GROUP_COMMIT rather than FSYNC_ON_COMMIT: this is a rebuildable projection, so a bounded window of
			// lost index writes costs a re-projection of those rows and never a lost fact. The link tables were
			// durable in Postgres before anything reached here.
			this.store = GraphStore.open(path, GraphStoreOptions.builder()
				.walSyncMode(WalSyncMode.GROUP_COMMIT)
				.walSyncIntervalMillis(50)
				.build());
			this.available = true;
			log.info("Asset graph index ready at {}", path);
		} catch (IOException | RuntimeException e) {
			this.available = false;
			log.error("The asset graph index at {} could not be opened; relatedness queries will be unavailable", path, e);
		}
	}

	// ---------------------------------------------------------------- writes

	@Override
	public void link(GraphEdge edge) {
		linkAll(List.of(edge));
	}

	@Override
	public void linkAll(List<GraphEdge> edges) {
		if (!available || edges.isEmpty()) {
			return;
		}
		guarded("link " + edges.size() + " edge(s)", () -> {
			try (Transaction txn = store.begin()) {
				for (GraphEdge edge : edges) {
					long from = ensureNode(edge.from());
					long to = ensureNode(edge.to());
					if (!hasEdge(from, edge.type(), to)) {
						store.createEdge(from, edge.type(), to);
					}
				}
				txn.commit();
			}
		});
	}

	@Override
	public void unlink(GraphEdge edge) {
		if (!available) {
			return;
		}
		guarded("unlink " + edge, () -> {
			Long from = findNode(edge.from());
			Long to = findNode(edge.to());
			if (from == null || to == null) {
				return;
			}
			store.deleteEdge(from, edge.type(), to);
		});
	}

	@Override
	public void remove(GraphNodeRef node) {
		if (!available) {
			return;
		}
		guarded("remove " + node, () -> {
			Long id = findNode(node);
			if (id != null) {
				store.deleteNode(id);
			}
		});
	}

	@Override
	public void rebuild(Stream<GraphEdge> all) {
		if (!available) {
			return;
		}
		guarded("rebuild", () -> {
			// Drop and recreate rather than diff. The index is derived, so the cheapest correct rebuild is the one
			// that cannot leave anything behind.
			store.close();
			deleteRecursively(path);
			store = GraphStore.open(path, GraphStoreOptions.builder()
				.walSyncMode(WalSyncMode.GROUP_COMMIT)
				.walSyncIntervalMillis(50)
				.build());

			List<GraphEdge> batch = new ArrayList<>(1000);
			long total = 0;
			for (GraphEdge edge : (Iterable<GraphEdge>) all::iterator) {
				batch.add(edge);
				if (batch.size() == 1000) {
					linkBatchInPlace(batch);
					total += batch.size();
					batch.clear();
				}
			}
			if (!batch.isEmpty()) {
				linkBatchInPlace(batch);
				total += batch.size();
			}
			store.checkpoint();
			log.info("Rebuilt the asset graph index from {} edges", total);
		});
	}

	/** The body of {@link #linkAll} without the availability guard, so the rebuild does not re-check it per batch. */
	private void linkBatchInPlace(List<GraphEdge> edges) throws IOException {
		try (Transaction txn = store.begin()) {
			for (GraphEdge edge : edges) {
				long from = ensureNode(edge.from());
				long to = ensureNode(edge.to());
				if (!hasEdge(from, edge.type(), to)) {
					store.createEdge(from, edge.type(), to);
				}
			}
			txn.commit();
		}
	}

	// ---------------------------------------------------------------- queries

	@Override
	public List<RelatedAsset> relatedAssets(RelatedAssetsQuery query) {
		if (!available) {
			return List.of();
		}
		Long start = findNode(GraphNodeRef.asset(query.assetUuid()));
		if (start == null) {
			return List.of();
		}

		// Hop one: out of the asset to whatever connects it to other assets.
		Map<UUID, Set<GraphNodeRef>> sharedBy = new HashMap<>();
		for (Edge first : store.neighbours(start, Direction.BOTH, query.viaTypes())) {
			GraphNodeRef intermediate = toRef(first.neighbourId());
			if (intermediate == null || intermediate.isAsset()) {
				continue;
			}
			// Hop two: back down to the other assets hanging off it.
			for (Edge second : store.neighbours(first.neighbourId(), Direction.BOTH, query.viaTypes())) {
				if (second.neighbourId() == start) {
					continue;
				}
				GraphNodeRef other = toRef(second.neighbourId());
				if (other == null || !other.isAsset()) {
					continue;
				}
				sharedBy.computeIfAbsent(other.uuid(), k -> new LinkedHashSet<>()).add(intermediate);
			}
		}

		List<RelatedAsset> hits = new ArrayList<>(sharedBy.size());
		sharedBy.forEach((uuid, via) -> {
			if (via.size() >= query.minSharedConnections()) {
				List<GraphNodeRef> ordered = new ArrayList<>(via);
				ordered.sort(Comparator.comparing(GraphNodeRef::kind).thenComparing(ref -> ref.uuid().toString()));
				hits.add(new RelatedAsset(uuid, via.size(), ordered));
			}
		});
		// Best first, then by uuid so that equal scores come back in a stable order rather than a hash order.
		hits.sort(Comparator.comparingInt(RelatedAsset::sharedConnections).reversed()
			.thenComparing(hit -> hit.assetUuid().toString()));
		return hits.size() > query.limit() ? new ArrayList<>(hits.subList(0, query.limit())) : hits;
	}

	@Override
	public List<GraphNodeRef> neighbours(GraphNodeRef node, Set<String> types) {
		if (!available) {
			return List.of();
		}
		Long id = findNode(node);
		if (id == null) {
			return List.of();
		}
		Set<GraphNodeRef> seen = new LinkedHashSet<>();
		for (Edge edge : store.neighbours(id, Direction.BOTH, types)) {
			GraphNodeRef ref = toRef(edge.neighbourId());
			if (ref != null) {
				seen.add(ref);
			}
		}
		List<GraphNodeRef> result = new ArrayList<>(seen);
		result.sort(Comparator.comparing(GraphNodeRef::kind).thenComparing(ref -> ref.uuid().toString()));
		return result;
	}

	@Override
	public boolean contains(GraphNodeRef node) {
		return available && findNode(node) != null;
	}

	@Override
	public Stream<UUID> streamIndexedAssetUuids() {
		if (!available) {
			return Stream.empty();
		}
		List<UUID> uuids = new ArrayList<>();
		for (long id : store.nodesByLabel(GraphNodeRef.KIND_ASSET)) {
			Object uuid = store.nodeProperties(id).get(PROP_UUID);
			if (uuid instanceof UUID value) {
				uuids.add(value);
			}
		}
		return uuids.stream();
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	public IndexStatus status() {
		IndexStatus status = new IndexStatus().setHealthy(available);
		if (!available) {
			return status.setDetail("the asset graph index at " + path + " is not open");
		}
		try {
			StoreStats stats = store.stats();
			long edges = 0;
			for (long id : store.nodesByLabel(GraphNodeRef.KIND_ASSET)) {
				edges += store.degreeOut(id) + store.degreeIn(id);
			}
			return status
				.setDocumentCount(stats.nodeCount())
				.setSizeBytes(stats.storageBytes())
				.setDetail(stats.nodeCount() + " nodes, " + edges + " asset edges, " + stats.segmentFileCount()
					+ " segment files");
		} catch (RuntimeException e) {
			return status.setHealthy(false).setDetail("status failed: " + e);
		}
	}

	@Override
	public void commit() {
		if (available) {
			guarded("commit", () -> store.checkpoint());
		}
	}

	@Override
	public void compact() {
		if (!available) {
			return;
		}
		guarded("compact", () -> {
			// Compaction invalidates the engine's internal ids. That is safe here and nowhere else in Loom, because
			// nothing outside this class ever sees one: every entry point takes a uuid and resolves it through the
			// property index on each call.
			CompactionReport report = store.compact(CompactionOptions.defaults());
			log.info("Compacted the asset graph index: {}", report);
		});
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public String providerName() {
		return PROVIDER_NAME;
	}

	public void close() {
		if (store != null) {
			try {
				store.close();
			} catch (IOException e) {
				log.warn("Failed to close the asset graph index at {}", path, e);
			}
			available = false;
		}
	}

	// ---------------------------------------------------------------- internals

	/** The engine node for a ref, creating it if this is the first edge that mentions it. */
	private long ensureNode(GraphNodeRef ref) throws IOException {
		Long existing = findNode(ref);
		if (existing != null) {
			return existing;
		}
		return store.createNode(ref.kind(), Properties.of().set(PROP_UUID, ref.uuid()));
	}

	/**
	 * The engine node for a ref, or null.
	 * <p>
	 * Through the property index rather than a cached map, so that a compaction — which relocates records and changes
	 * every id — cannot leave a stale answer behind.
	 */
	private Long findNode(GraphNodeRef ref) {
		for (long id : store.nodesByProperty(PROP_UUID, ref.uuid())) {
			if (ref.kind().equals(store.nodeLabel(id))) {
				return id;
			}
		}
		return null;
	}

	private GraphNodeRef toRef(long nodeId) {
		Object uuid = store.nodeProperties(nodeId).get(PROP_UUID);
		if (!(uuid instanceof UUID value)) {
			return null;
		}
		return new GraphNodeRef(store.nodeLabel(nodeId), value);
	}

	private boolean hasEdge(long from, String type, long to) {
		for (Edge edge : store.neighbours(from, Direction.OUT, Set.of(type))) {
			if (edge.neighbourId() == to) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Runs an index write, and treats a failure as a failure of the index rather than of the caller.
	 * <p>
	 * This is the contract the SPI states: Postgres has already accepted the fact, so an index write that fails is a
	 * reason to log and rebuild later, never a reason to fail the request that produced it.
	 */
	private void guarded(String what, Unit unit) {
		try {
			unit.run();
		} catch (IOException | RuntimeException e) {
			log.error("Asset graph index operation failed ({}); the index may be stale until it is rebuilt", what, e);
		}
	}

	@FunctionalInterface
	private interface Unit {
		void run() throws IOException;
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!java.nio.file.Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = java.nio.file.Files.walk(root)) {
			for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
				java.nio.file.Files.deleteIfExists(entry);
			}
		}
	}

	/** Visible for the differential test, which needs to know the shape of what was projected. */
	public Set<String> indexedKinds() {
		Set<String> kinds = new HashSet<>();
		if (!available) {
			return kinds;
		}
		for (String kind : new String[] { GraphNodeRef.KIND_ASSET, GraphNodeRef.KIND_TAG, GraphNodeRef.KIND_COLLECTION,
			GraphNodeRef.KIND_REMIX, GraphNodeRef.KIND_PERSON }) {
			if (!store.nodesByLabel(kind).isEmpty()) {
				kinds.add(kind);
			}
		}
		return kinds;
	}
}
