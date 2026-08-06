package io.metaloom.loom.api.options;

/**
 * Options which control the embedding vector index - the approximate nearest-neighbour structure behind face matching and, later, semantic search.
 *
 * <p>
 * Like search and fingerprint similarity, this is a capability rather than a dependency: when no provider is selected or the chosen one cannot be
 * opened, Loom still boots, embeddings are still produced and still stored in Postgres, and only the similarity queries are unavailable. Because
 * {@code embedding} is the system of record and unsynced rows stay {@code dirty}, turning a provider on later and rebuilding picks up everything
 * written while it was off - running without one costs query ability, never data.
 * </p>
 *
 * <p>
 * Disabled by default: the index is only useful once a node has written embeddings, and standing it up costs disk.
 * </p>
 */
public class VectorIndexOptions implements Option {

	/** No index. See {@code NoopVectorIndex}. */
	public static final String PROVIDER_NONE = "none";

	/** On-disk Lucene HNSW index. See {@code LuceneVectorIndex}. */
	public static final String PROVIDER_LUCENE = "lucene";

	public static final int DEFAULT_TOPK = 10;

	public static final float DEFAULT_SCORE_THRESHOLD = 0.35f;

	public static final int DEFAULT_SYNC_INTERVAL_MS = 5000;

	public static final int DEFAULT_SYNC_BATCH_SIZE = 500;

	@EnvironmentVariable(name = "LOOM_VECTOR_INDEX_PROVIDER", description = "Which vector index backend to bind: none or lucene. The embedding table is the system of record either way, so this can be changed and the index rebuilt without losing vectors.")
	private String provider = PROVIDER_NONE;

	@EnvironmentVariable(name = "LOOM_VECTOR_INDEX_PATH", description = "Directory holding the on-disk vector index. Separate from the fingerprint similarity index. Rebuildable from the embedding table at any time.")
	private String indexPath = "vector-index";

	@EnvironmentVariable(name = "LOOM_VECTOR_INDEX_TOPK", description = "Default number of neighbours returned per query; overridable per request.")
	private int topK = DEFAULT_TOPK;

	@EnvironmentVariable(name = "LOOM_VECTOR_INDEX_SCORE_THRESHOLD", description = "Default similarity floor. Hits below this are dropped; overridable per request.")
	private float scoreThreshold = DEFAULT_SCORE_THRESHOLD;

	@EnvironmentVariable(name = "LOOM_VECTOR_INDEX_SYNC_INTERVAL_MS", description = "How often the dirty embedding rows are drained into the index. Set to 0 to disable the background drain and rely on the write hook and manual rebuilds alone.")
	private int syncIntervalMs = DEFAULT_SYNC_INTERVAL_MS;

	@EnvironmentVariable(name = "LOOM_VECTOR_INDEX_SYNC_BATCH_SIZE", description = "Maximum embedding rows drained into the index per sync pass.")
	private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;

	public String getProvider() {
		return provider;
	}

	public VectorIndexOptions setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	/**
	 * Return whether a real backend is selected. Convenience for the boot wiring, which otherwise repeats the "none" comparison in several places.
	 */
	public boolean isEnabled() {
		return provider != null && !PROVIDER_NONE.equalsIgnoreCase(provider);
	}

	public String getIndexPath() {
		return indexPath;
	}

	public VectorIndexOptions setIndexPath(String indexPath) {
		this.indexPath = indexPath;
		return this;
	}

	public int getTopK() {
		return topK;
	}

	public VectorIndexOptions setTopK(int topK) {
		this.topK = topK;
		return this;
	}

	public float getScoreThreshold() {
		return scoreThreshold;
	}

	public VectorIndexOptions setScoreThreshold(float scoreThreshold) {
		this.scoreThreshold = scoreThreshold;
		return this;
	}

	public int getSyncIntervalMs() {
		return syncIntervalMs;
	}

	public VectorIndexOptions setSyncIntervalMs(int syncIntervalMs) {
		this.syncIntervalMs = syncIntervalMs;
		return this;
	}

	public int getSyncBatchSize() {
		return syncBatchSize;
	}

	public VectorIndexOptions setSyncBatchSize(int syncBatchSize) {
		this.syncBatchSize = syncBatchSize;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (provider == null || provider.isBlank()) {
			errors.add("provider", "The vector index provider (LOOM_VECTOR_INDEX_PROVIDER) must be set; use 'none' to disable it.");
			return;
		}
		if (!PROVIDER_NONE.equalsIgnoreCase(provider) && !PROVIDER_LUCENE.equalsIgnoreCase(provider)) {
			// Named explicitly rather than silently falling back, so a typo in the provider name cannot
			// look like a working index that quietly answers nothing.
			errors.add("provider", "Unknown vector index provider '" + provider + "' (LOOM_VECTOR_INDEX_PROVIDER). Supported: "
				+ PROVIDER_NONE + ", " + PROVIDER_LUCENE + ".");
			return;
		}
		if (!isEnabled()) {
			return;
		}
		if (indexPath == null || indexPath.isBlank()) {
			errors.add("indexPath", "The vector index path (LOOM_VECTOR_INDEX_PATH) must be set when a provider is selected.");
		}
		if (scoreThreshold < 0f) {
			errors.add("scoreThreshold", "The vector index score threshold (LOOM_VECTOR_INDEX_SCORE_THRESHOLD) must not be negative.");
		}
		if (syncIntervalMs < 0) {
			errors.add("syncIntervalMs", "The vector index sync interval (LOOM_VECTOR_INDEX_SYNC_INTERVAL_MS) must not be negative.");
		}
		errors.min("topK", topK, 1);
		errors.min("syncBatchSize", syncBatchSize, 1);
	}
}
