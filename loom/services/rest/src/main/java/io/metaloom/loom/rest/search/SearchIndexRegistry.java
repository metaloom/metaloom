package io.metaloom.loom.rest.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.api.options.VectorIndexOptions;
import io.metaloom.loom.api.search.IndexStatus;
import io.metaloom.loom.api.search.SearchIndexId;
import io.metaloom.loom.api.search.SearchIndexer;
import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorSpace;
import io.metaloom.loom.db.jooq.search.SearchEmbeddingService;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao.EmbeddingSpaceStats;

/**
 * Discovers which indices exist and what state they are in.
 *
 * <p>
 * Everything here is computed per request. There is no registry of indices in the database, and there deliberately is not one: a vector space exists
 * because rows with that {@code (type, model, dimensions)} triple exist, so the table is the register. Adding a declaration table would create a
 * second source of truth that could disagree with the vectors themselves.
 * </p>
 *
 * <p>
 * <b>Nothing here throws.</b> Every status read is defensive, because this is the screen an operator opens when something is already wrong: an index
 * whose directory failed to open must show up as broken with a reason, not take the whole list down with it.
 * </p>
 */
@Singleton
public class SearchIndexRegistry {

	private static final Logger log = LoggerFactory.getLogger(SearchIndexRegistry.class);

	private static final List<IndexJobAction> VECTOR_ACTIONS = List.of(IndexJobAction.REINDEX, IndexJobAction.DELTA_SYNC, IndexJobAction.DROP);

	private static final List<IndexJobAction> FINGERPRINT_ACTIONS = List.of(IndexJobAction.REINDEX, IndexJobAction.DELTA_SYNC, IndexJobAction.DROP);

	/**
	 * The lexical index supports a rebuild and nothing else.
	 *
	 * <p>
	 * No delta sync, because the triggers that maintain {@code search_document} run inside the transaction that writes the source row - there is no
	 * window in which it can be behind. No drop, because emptying it would not release the storage an operator is usually after (the table stays, and
	 * the next write to any asset silently repopulates part of it) while making search answer nothing in the meantime. A rebuild covers every case a
	 * drop was reached for.
	 * </p>
	 */
	private static final List<IndexJobAction> LEXICAL_ACTIONS = List.of(IndexJobAction.REINDEX);

	private final SearchIndexer searchIndexer;
	private final SearchOptions searchOptions;
	private final VectorIndex vectorIndex;
	private final VectorIndexOptions vectorOptions;
	private final EmbeddingDao embeddingDao;
	private final SearchEmbeddingService searchEmbeddingService;
	private final SimilarityIndex similarityIndex;
	private final SimilarityOptions similarityOptions;
	private final AssetComponentDao compDao;

	@Inject
	public SearchIndexRegistry(SearchIndexer searchIndexer, SearchOptions searchOptions, VectorIndex vectorIndex, VectorIndexOptions vectorOptions,
		EmbeddingDao embeddingDao, SearchEmbeddingService searchEmbeddingService, SimilarityIndex similarityIndex,
		SimilarityOptions similarityOptions, AssetComponentDao compDao) {
		this.searchIndexer = searchIndexer;
		this.searchOptions = searchOptions;
		this.vectorIndex = vectorIndex;
		this.vectorOptions = vectorOptions;
		this.embeddingDao = embeddingDao;
		this.searchEmbeddingService = searchEmbeddingService;
		this.similarityIndex = similarityIndex;
		this.similarityOptions = similarityOptions;
		this.compDao = compDao;
	}

	/** Every index, in a stable order: lexical, then the vector spaces, then fingerprints. */
	public List<SearchIndexDescriptor> list() {
		List<SearchIndexDescriptor> indices = new ArrayList<>();
		indices.add(lexical());
		indices.addAll(vectorSpaces());
		indices.add(fingerprint());
		return indices;
	}

	/**
	 * Resolve an id against the live list.
	 *
	 * <p>
	 * By lookup, never by parsing: the id is a slug of a model name that may contain characters the slug drops, so it is not reversible. That is a
	 * feature - it means an id can only ever name an index that exists.
	 * </p>
	 *
	 * @return the descriptor, or null when nothing matches
	 */
	public SearchIndexDescriptor find(String id) {
		if (id == null) {
			return null;
		}
		for (SearchIndexDescriptor descriptor : list()) {
			if (descriptor.id().equals(id)) {
				return descriptor;
			}
		}
		return null;
	}

	/** The backends the listed indices live in. */
	public List<SearchIndexBackend> backends() {
		List<SearchIndexBackend> backends = new ArrayList<>();

		IndexStatus lexical = statusOf(searchIndexer::status, "the lexical index");
		backends.add(new SearchIndexBackend(SearchIndexBackend.LEXICAL, searchOptions.getProvider(),
			searchOptions.isEnabled(), lexical.isHealthy(), lexical.getDetail(),
			lexical.getDocumentCount(), 0, lexical.getSizeBytes()));

		IndexStatus vector = statusOf(vectorIndex::status, "the vector index");
		backends.add(new SearchIndexBackend(SearchIndexBackend.VECTOR, vectorIndex.providerName(),
			vectorOptions.isEnabled(), vectorIndex.isAvailable(), vector.getDetail(),
			vector.getDocumentCount(), vector.getDeletedCount(), vector.getSizeBytes()));

		IndexStatus fingerprint = statusOf(similarityIndex::status, "the fingerprint index");
		backends.add(new SearchIndexBackend(SearchIndexBackend.FINGERPRINT, similarityIndex.providerName(),
			similarityOptions.isEnabled(), similarityIndex.isAvailable(), fingerprint.getDetail(),
			fingerprint.getDocumentCount(), fingerprint.getDeletedCount(), fingerprint.getSizeBytes()));

		return backends;
	}

	// ---------------------------------------------------------------------------------------------

	private SearchIndexDescriptor lexical() {
		IndexStatus status = statusOf(searchIndexer::status, "the lexical index");
		boolean enabled = searchOptions.isEnabled() && !SearchOptions.PROVIDER_NONE.equals(searchOptions.getProvider());
		return new SearchIndexDescriptor(
			SearchIndexId.LEXICAL,
			SearchIndexKind.LEXICAL,
			SearchIndexBackend.LEXICAL,
			"Lexical search documents",
			null,
			null,
			enabled,
			enabled && status.isHealthy(),
			enabled ? status.getDetail() : "Search is disabled (LOOM_SEARCH_ENABLED=false or LOOM_SEARCH_PROVIDER=none).",
			status.getDocumentCount(),
			status.getDocumentCount(),
			// Triggers maintain the table inside the writing transaction, so it is never behind. Reporting
			// the provider's dirtyCount here would be misleading in the other direction: SearchEndpointService
			// substitutes the *semantic* backlog into that field, and that backlog belongs to the space below.
			0,
			null,
			LEXICAL_ACTIONS);
	}

	/**
	 * One descriptor per {@code (type, model, dimensions)} triple in the {@code embedding} table.
	 *
	 * <p>
	 * The configured semantic space is added even when it has no rows yet. Without that, a freshly enabled semantic search - the exact moment an
	 * operator wants to watch the backlog drain - would show no index at all, because "which indices exist" is otherwise answered by "which vectors
	 * have been written", and none have.
	 * </p>
	 */
	private List<SearchIndexDescriptor> vectorSpaces() {
		Map<String, SearchIndexDescriptor> byId = new LinkedHashMap<>();
		Set<String> seen = new LinkedHashSet<>();

		List<EmbeddingSpaceStats> spaces;
		try {
			spaces = embeddingDao.listSpaces();
		} catch (Exception e) {
			log.warn("Could not enumerate vector spaces: {}", e.getMessage());
			spaces = List.of();
		}

		for (EmbeddingSpaceStats stats : spaces) {
			VectorSpace space = new VectorSpace(stats.type(), stats.model(), stats.dimensions());
			seen.add(space.key());
			byId.put(SearchIndexId.of(space), vectorDescriptor(space, stats.total(), stats.dirty()));
		}

		VectorSpace semantic = semanticSpace();
		if (semantic != null && !seen.contains(semantic.key())) {
			byId.putIfAbsent(SearchIndexId.of(semantic), vectorDescriptor(semantic, 0, 0));
		}
		return List.copyOf(byId.values());
	}

	private SearchIndexDescriptor vectorDescriptor(VectorSpace space, long total, long dirty) {
		IndexStatus status = statusOf(() -> vectorIndex.status(space), "vector space " + space.key());
		long pending = dirty;
		if (isSemanticSpace(space)) {
			// The dirty flag only covers vectors already written but not yet indexed. For the search-text
			// space the larger backlog is upstream of that: documents whose text changed since they were
			// last embedded, which have no row to mark dirty. Reporting only the flag would show 0 while
			// a fresh import of ten thousand assets is still waiting for the embedding host.
			pending += pendingEmbeddings();
		}
		return new SearchIndexDescriptor(
			SearchIndexId.of(space),
			SearchIndexKind.VECTOR,
			SearchIndexBackend.VECTOR,
			label(space),
			space,
			null,
			vectorOptions.isEnabled(),
			vectorIndex.isAvailable(),
			vectorIndex.isAvailable() ? status.getDetail() : unavailableVectorReason(),
			total,
			status.getDocumentCount(),
			pending,
			null,
			VECTOR_ACTIONS);
	}

	private SearchIndexDescriptor fingerprint() {
		String algorithm = similarityOptions.getAlgorithm();
		IndexStatus status = statusOf(() -> similarityIndex.status(algorithm), "the fingerprint index");
		long total = 0;
		try {
			total = compDao.countByAlgorithm(algorithm);
		} catch (Exception e) {
			log.warn("Could not count fingerprint components: {}", e.getMessage());
		}
		long indexed = status.getDocumentCount();
		return new SearchIndexDescriptor(
			SearchIndexId.ofAlgorithm(algorithm, algorithm),
			SearchIndexKind.FINGERPRINT,
			SearchIndexBackend.FINGERPRINT,
			"Duplicate fingerprints",
			null,
			algorithm,
			similarityOptions.isEnabled(),
			similarityIndex.isAvailable(),
			similarityIndex.isAvailable() ? status.getDetail() : unavailableFingerprintReason(),
			total,
			indexed,
			// No freshness flag exists on asset_fingerprint_comp, so the backlog is inferred from the gap.
			// It reads 0 when the index holds MORE than the table - that case is drift in the other
			// direction, which the two counts show plainly and a delta sync corrects.
			Math.max(0, total - indexed),
			null,
			FINGERPRINT_ACTIONS);
	}

	// ---------------------------------------------------------------------------------------------

	/** The space the semantic text embedder writes into, or null when semantic search is not configured. */
	public VectorSpace semanticSpace() {
		if (!searchOptions.isSemanticEnabled()) {
			return null;
		}
		String model = searchOptions.getEmbedModel();
		if (model == null || model.isBlank()) {
			return null;
		}
		return new VectorSpace(searchOptions.getVectorType(), model, searchOptions.getEmbedDimensions());
	}

	public boolean isSemanticSpace(VectorSpace space) {
		VectorSpace semantic = semanticSpace();
		return semantic != null && semantic.key().equals(space.key());
	}

	private long pendingEmbeddings() {
		try {
			return searchEmbeddingService.isReady() ? searchEmbeddingService.pendingCount() : 0;
		} catch (Exception e) {
			log.warn("Could not count pending search embeddings: {}", e.getMessage());
			return 0;
		}
	}

	private String label(VectorSpace space) {
		if (isSemanticSpace(space)) {
			return "Semantic text embeddings";
		}
		return switch (space.type()) {
			case "face" -> "Face embeddings";
			default -> space.type() + " embeddings";
		};
	}

	private String unavailableVectorReason() {
		return vectorOptions.isEnabled()
			? "The vector index is configured but could not be opened."
			: "No vector index backend is bound (LOOM_VECTOR_INDEX_PROVIDER=none).";
	}

	private String unavailableFingerprintReason() {
		return similarityOptions.isEnabled()
			? "The fingerprint index is configured but could not be opened."
			: "The fingerprint index is disabled (LOOM_SIMILARITY_ENABLED=false).";
	}

	/**
	 * Read a status without letting a broken backend take the list with it.
	 *
	 * <p>
	 * The SPI already promises its status methods never throw, but this list is the one screen that has to render when that promise is broken - a
	 * backend that fails on the way to reporting that it is failing would otherwise 500 the whole page.
	 * </p>
	 */
	private IndexStatus statusOf(java.util.function.Supplier<IndexStatus> supplier, String what) {
		try {
			IndexStatus status = supplier.get();
			return status == null ? new IndexStatus() : status;
		} catch (Exception e) {
			log.warn("Could not read the status of {}: {}", what, e.getMessage());
			return new IndexStatus().setHealthy(false).setDetail(e.getMessage());
		}
	}
}
