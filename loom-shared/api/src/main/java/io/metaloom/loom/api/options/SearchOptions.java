package io.metaloom.loom.api.options;

/**
 * Options which control search.
 *
 * <p>
 * Search is a capability, not a dependency: when it is disabled or its provider fails to start, Loom still boots and every other route keeps working.
 * The search routes then answer 503 and {@code GET /api/v1/search/status} answers 200 with {@code available: false}, so the UI can hide the search bar
 * rather than render one that errors on every keystroke.
 * </p>
 */
public class SearchOptions implements Option {

	public static final String PROVIDER_POSTGRES = "postgres";

	public static final String PROVIDER_ELASTICSEARCH = "elasticsearch";

	public static final String PROVIDER_NONE = "none";

	public static final int DEFAULT_LIMIT = 25;

	public static final int DEFAULT_MAX_LIMIT = 100;

	public static final int DEFAULT_MAX_OFFSET = 1000;

	public static final int DEFAULT_BODY_MAX_BYTES = 512 * 1024;

	public static final double DEFAULT_TRIGRAM_THRESHOLD = 0.3d;

	public static final double DEFAULT_TRIGRAM_WEIGHT = 0.35d;

	public static final String DEFAULT_TS_CONFIG = "english";

	/**
	 * {@code embedding.type} the text→media path writes and queries.
	 *
	 * <p>
	 * Deliberately not {@code clip}: these vectors come from a text model reading the document Loom already assembled for lexical search, not from a
	 * vision model reading the pixels. Keeping the types distinct is what lets both coexist later - a {@code clip} space can be added beside this one
	 * without either invalidating the other.
	 * </p>
	 */
	public static final String DEFAULT_VECTOR_TYPE = "text";

	/** Node kind stamped on the embeddings this path writes, distinguishing them from a node's output in the same table. */
	public static final String VECTOR_NODE_KIND = "search";

	public static final int DEFAULT_VECTOR_TOPK = 200;

	public static final int DEFAULT_RRF_K = 60;

	public static final double DEFAULT_RRF_WEIGHT_LEXICAL = 1.0d;

	public static final double DEFAULT_RRF_WEIGHT_VECTOR = 1.0d;

	public static final int DEFAULT_EMBED_DIMENSIONS = 768;

	public static final int DEFAULT_EMBED_TIMEOUT_MS = 10_000;

	public static final int DEFAULT_EMBED_BATCH_SIZE = 16;

	public static final int DEFAULT_EMBED_MAX_CHARS = 8_000;

	public static final int DEFAULT_EMBED_SYNC_INTERVAL_MS = 15_000;

	public static final float DEFAULT_VECTOR_MIN_SCORE = 0.0f;

	@EnvironmentVariable(name = "LOOM_SEARCH_ENABLED", description = "Master switch for search. When off the search routes answer 503.")
	private boolean enabled = true;

	@EnvironmentVariable(name = "LOOM_SEARCH_PROVIDER", description = "Search backend: postgres, elasticsearch or none.")
	private String provider = PROVIDER_POSTGRES;

	@EnvironmentVariable(name = "LOOM_SEARCH_DEFAULT_LIMIT", description = "Default page size for search results.")
	private int defaultLimit = DEFAULT_LIMIT;

	@EnvironmentVariable(name = "LOOM_SEARCH_MAX_LIMIT", description = "Maximum page size a caller may request.")
	private int maxLimit = DEFAULT_MAX_LIMIT;

	@EnvironmentVariable(name = "LOOM_SEARCH_MAX_OFFSET", description = "Deep-paging guard. Offsets beyond this are rejected with 400 rather than run as a table scan.")
	private int maxOffset = DEFAULT_MAX_OFFSET;

	@EnvironmentVariable(name = "LOOM_SEARCH_HIGHLIGHT_ENABLED", description = "Allow match snippets. ts_headline is O(document size) and cannot use an index, so it runs only for the returned page.")
	private boolean highlightEnabled = true;

	@EnvironmentVariable(name = "LOOM_SEARCH_TRIGRAM_THRESHOLD", description = "pg_trgm similarity threshold below which a fuzzy match is discarded.")
	private double trigramThreshold = DEFAULT_TRIGRAM_THRESHOLD;

	@EnvironmentVariable(name = "LOOM_SEARCH_TRIGRAM_WEIGHT", description = "Weight of the trigram similarity term in the blended Postgres score.")
	private double trigramWeight = DEFAULT_TRIGRAM_WEIGHT;

	@EnvironmentVariable(name = "LOOM_SEARCH_BODY_MAX_BYTES", description = "Cap on indexed body text. A tsvector is limited to 1 MB, so long extractions must be truncated.")
	private int bodyMaxBytes = DEFAULT_BODY_MAX_BYTES;

	@EnvironmentVariable(name = "LOOM_SEARCH_TS_CONFIG", description = "Postgres text search configuration used for the stemmed index column.")
	private String tsConfig = DEFAULT_TS_CONFIG;

	@EnvironmentVariable(name = "LOOM_SEARCH_SEMANTIC_ENABLED", description = "Enable semantic and hybrid search. Off by default because it needs an embedding host; when off the provider advertises neither capability and rejects those modes.")
	private boolean semanticEnabled = false;

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_URL", description = "Base URL of an OpenAI-compatible embeddings host, e.g. http://127.0.0.1:8090/v1 for llama.cpp started with --embeddings. Empty disables semantic search.")
	private String embedUrl = "";

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_MODEL", description = "Model name sent to the embeddings host, and the model discriminator stored on each embedding row so two models can coexist.")
	private String embedModel = "";

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_API_KEY", description = "Bearer token for the embeddings host. Empty for a local llama.cpp, which needs none.")
	private String embedApiKey = "";

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_DIMENSIONS", description = "Vector length the embedding model produces. A reply of a different length is rejected rather than stored, because a wrong value here silently poisons an index segment.")
	private int embedDimensions = DEFAULT_EMBED_DIMENSIONS;

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_TIMEOUT_MS", description = "Request timeout for the embeddings host.")
	private int embedTimeoutMs = DEFAULT_EMBED_TIMEOUT_MS;

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_BATCH_SIZE", description = "Documents embedded per request while indexing the catalog.")
	private int embedBatchSize = DEFAULT_EMBED_BATCH_SIZE;

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_MAX_CHARS", description = "Cap on the document text sent to the embedding model. A transcript far exceeds any model's context window, and the overflow is silently dropped by the host rather than reported.")
	private int embedMaxChars = DEFAULT_EMBED_MAX_CHARS;

	@EnvironmentVariable(name = "LOOM_SEARCH_EMBED_SYNC_INTERVAL_MS", description = "How often stale search documents are re-embedded. 0 disables the background pass, leaving only the manual rebuild route.")
	private int embedSyncIntervalMs = DEFAULT_EMBED_SYNC_INTERVAL_MS;

	@EnvironmentVariable(name = "LOOM_SEARCH_VECTOR_TYPE", description = "The embedding.type that participates in text search. Isolates these vectors from face vectors in the same table.")
	private String vectorType = DEFAULT_VECTOR_TYPE;

	@EnvironmentVariable(name = "LOOM_SEARCH_VECTOR_TOPK", description = "Candidates pulled from each ranker before fusion. Larger trades latency for recall, especially with filters applied.")
	private int vectorTopK = DEFAULT_VECTOR_TOPK;

	@EnvironmentVariable(name = "LOOM_SEARCH_VECTOR_MIN_SCORE", description = "Similarity floor for a vector candidate. NOT a cosine and not a percentage: the scale is the vector index backend's. Under the Lucene index (Euclidean, 1/(1+d2)) unit vectors score 1.0 identical, 0.33 unrelated and 0.2 opposite, so a useful floor is around 0.5-0.6. 0 keeps every neighbour the index returns and lets fusion decide.")
	private double vectorMinScore = DEFAULT_VECTOR_MIN_SCORE;

	@EnvironmentVariable(name = "LOOM_SEARCH_RRF_K", description = "Reciprocal Rank Fusion constant. Larger flattens the advantage of top ranks.")
	private int rrfK = DEFAULT_RRF_K;

	@EnvironmentVariable(name = "LOOM_SEARCH_RRF_WEIGHT_LEXICAL", description = "Weight of the lexical ranker in hybrid fusion.")
	private double rrfWeightLexical = DEFAULT_RRF_WEIGHT_LEXICAL;

	@EnvironmentVariable(name = "LOOM_SEARCH_RRF_WEIGHT_VECTOR", description = "Weight of the vector ranker in hybrid fusion.")
	private double rrfWeightVector = DEFAULT_RRF_WEIGHT_VECTOR;

	public boolean isEnabled() {
		return enabled;
	}

	public SearchOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getProvider() {
		return provider;
	}

	public SearchOptions setProvider(String provider) {
		this.provider = provider;
		return this;
	}

	public int getDefaultLimit() {
		return defaultLimit;
	}

	public SearchOptions setDefaultLimit(int defaultLimit) {
		this.defaultLimit = defaultLimit;
		return this;
	}

	public int getMaxLimit() {
		return maxLimit;
	}

	public SearchOptions setMaxLimit(int maxLimit) {
		this.maxLimit = maxLimit;
		return this;
	}

	public int getMaxOffset() {
		return maxOffset;
	}

	public SearchOptions setMaxOffset(int maxOffset) {
		this.maxOffset = maxOffset;
		return this;
	}

	public boolean isHighlightEnabled() {
		return highlightEnabled;
	}

	public SearchOptions setHighlightEnabled(boolean highlightEnabled) {
		this.highlightEnabled = highlightEnabled;
		return this;
	}

	public double getTrigramThreshold() {
		return trigramThreshold;
	}

	public SearchOptions setTrigramThreshold(double trigramThreshold) {
		this.trigramThreshold = trigramThreshold;
		return this;
	}

	public double getTrigramWeight() {
		return trigramWeight;
	}

	public SearchOptions setTrigramWeight(double trigramWeight) {
		this.trigramWeight = trigramWeight;
		return this;
	}

	public int getBodyMaxBytes() {
		return bodyMaxBytes;
	}

	public SearchOptions setBodyMaxBytes(int bodyMaxBytes) {
		this.bodyMaxBytes = bodyMaxBytes;
		return this;
	}

	public String getTsConfig() {
		return tsConfig;
	}

	public SearchOptions setTsConfig(String tsConfig) {
		this.tsConfig = tsConfig;
		return this;
	}

	public boolean isSemanticEnabled() {
		return semanticEnabled;
	}

	public SearchOptions setSemanticEnabled(boolean semanticEnabled) {
		this.semanticEnabled = semanticEnabled;
		return this;
	}

	public String getEmbedUrl() {
		return embedUrl;
	}

	public SearchOptions setEmbedUrl(String embedUrl) {
		this.embedUrl = embedUrl;
		return this;
	}

	public String getEmbedModel() {
		return embedModel;
	}

	public SearchOptions setEmbedModel(String embedModel) {
		this.embedModel = embedModel;
		return this;
	}

	public String getEmbedApiKey() {
		return embedApiKey;
	}

	public SearchOptions setEmbedApiKey(String embedApiKey) {
		this.embedApiKey = embedApiKey;
		return this;
	}

	public int getEmbedDimensions() {
		return embedDimensions;
	}

	public SearchOptions setEmbedDimensions(int embedDimensions) {
		this.embedDimensions = embedDimensions;
		return this;
	}

	public int getEmbedTimeoutMs() {
		return embedTimeoutMs;
	}

	public SearchOptions setEmbedTimeoutMs(int embedTimeoutMs) {
		this.embedTimeoutMs = embedTimeoutMs;
		return this;
	}

	public int getEmbedBatchSize() {
		return embedBatchSize;
	}

	public SearchOptions setEmbedBatchSize(int embedBatchSize) {
		this.embedBatchSize = embedBatchSize;
		return this;
	}

	public int getEmbedMaxChars() {
		return embedMaxChars;
	}

	public SearchOptions setEmbedMaxChars(int embedMaxChars) {
		this.embedMaxChars = embedMaxChars;
		return this;
	}

	public int getEmbedSyncIntervalMs() {
		return embedSyncIntervalMs;
	}

	public SearchOptions setEmbedSyncIntervalMs(int embedSyncIntervalMs) {
		this.embedSyncIntervalMs = embedSyncIntervalMs;
		return this;
	}

	public String getVectorType() {
		return vectorType;
	}

	public SearchOptions setVectorType(String vectorType) {
		this.vectorType = vectorType;
		return this;
	}

	public int getVectorTopK() {
		return vectorTopK;
	}

	public SearchOptions setVectorTopK(int vectorTopK) {
		this.vectorTopK = vectorTopK;
		return this;
	}

	public double getVectorMinScore() {
		return vectorMinScore;
	}

	public SearchOptions setVectorMinScore(double vectorMinScore) {
		this.vectorMinScore = vectorMinScore;
		return this;
	}

	public int getRrfK() {
		return rrfK;
	}

	public SearchOptions setRrfK(int rrfK) {
		this.rrfK = rrfK;
		return this;
	}

	public double getRrfWeightLexical() {
		return rrfWeightLexical;
	}

	public SearchOptions setRrfWeightLexical(double rrfWeightLexical) {
		this.rrfWeightLexical = rrfWeightLexical;
		return this;
	}

	public double getRrfWeightVector() {
		return rrfWeightVector;
	}

	public SearchOptions setRrfWeightVector(double rrfWeightVector) {
		this.rrfWeightVector = rrfWeightVector;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (!enabled) {
			return;
		}
		if (provider == null || provider.isBlank()) {
			errors.add("provider", "The search provider (LOOM_SEARCH_PROVIDER) must be set. Use 'none' to disable search explicitly.");
		} else if (!PROVIDER_POSTGRES.equalsIgnoreCase(provider)
			&& !PROVIDER_ELASTICSEARCH.equalsIgnoreCase(provider)
			&& !PROVIDER_NONE.equalsIgnoreCase(provider)) {
			errors.add("provider", "Unknown search provider '" + provider + "' (LOOM_SEARCH_PROVIDER). Expected one of: "
				+ PROVIDER_POSTGRES + ", " + PROVIDER_ELASTICSEARCH + ", " + PROVIDER_NONE + ".");
		}
		errors.min("defaultLimit", defaultLimit, 1)
			.min("maxLimit", maxLimit, 1)
			.min("maxOffset", maxOffset, 0)
			.min("bodyMaxBytes", bodyMaxBytes, 1024);
		if (defaultLimit > maxLimit) {
			errors.add("defaultLimit", "The default page size (LOOM_SEARCH_DEFAULT_LIMIT) must not exceed the maximum (LOOM_SEARCH_MAX_LIMIT).");
		}
		if (trigramThreshold < 0 || trigramThreshold > 1) {
			errors.add("trigramThreshold", "The trigram threshold (LOOM_SEARCH_TRIGRAM_THRESHOLD) must be between 0 and 1.");
		}
		if (trigramWeight < 0) {
			errors.add("trigramWeight", "The trigram weight (LOOM_SEARCH_TRIGRAM_WEIGHT) must not be negative.");
		}
		if (tsConfig == null || tsConfig.isBlank()) {
			errors.add("tsConfig", "The text search configuration (LOOM_SEARCH_TS_CONFIG) must be set, e.g. 'english' or 'simple'.");
		}
		validateSemantic(errors);
	}

	/**
	 * Validate the semantic half.
	 *
	 * <p>
	 * Everything here is skipped while {@code semanticEnabled} is false, which is the default: an operator who never turns semantic search on must not
	 * have to configure an embedding host to boot. Once it is on, an unreachable or unnamed host is a configuration error rather than something to
	 * discover on the first query - the whole point of the capability model is that the server knows what it can do before anyone asks.
	 * </p>
	 */
	private void validateSemantic(OptionErrors errors) {
		if (!semanticEnabled) {
			return;
		}
		if (embedUrl == null || embedUrl.isBlank()) {
			errors.add("embedUrl", "Semantic search is enabled (LOOM_SEARCH_SEMANTIC_ENABLED=true) but no embedding host is set (LOOM_SEARCH_EMBED_URL).");
		}
		if (embedModel == null || embedModel.isBlank()) {
			errors.add("embedModel", "Semantic search is enabled but no embedding model is named (LOOM_SEARCH_EMBED_MODEL). The name is also the "
				+ "discriminator stored on every vector, so it must not be blank.");
		}
		errors.min("embedDimensions", embedDimensions, 1)
			.min("embedTimeoutMs", embedTimeoutMs, 1)
			.min("embedBatchSize", embedBatchSize, 1)
			.min("embedMaxChars", embedMaxChars, 1)
			.min("embedSyncIntervalMs", embedSyncIntervalMs, 0)
			.min("vectorTopK", vectorTopK, 1)
			.min("rrfK", rrfK, 1);
		if (vectorType == null || vectorType.isBlank()) {
			errors.add("vectorType", "The searchable embedding type (LOOM_SEARCH_VECTOR_TYPE) must be set.");
		}
		if (vectorMinScore < 0 || vectorMinScore > 1) {
			errors.add("vectorMinScore", "The vector score floor (LOOM_SEARCH_VECTOR_MIN_SCORE) must be between 0 and 1.");
		}
		if (rrfWeightLexical < 0) {
			errors.add("rrfWeightLexical", "The lexical fusion weight (LOOM_SEARCH_RRF_WEIGHT_LEXICAL) must not be negative.");
		}
		if (rrfWeightVector < 0) {
			errors.add("rrfWeightVector", "The vector fusion weight (LOOM_SEARCH_RRF_WEIGHT_VECTOR) must not be negative.");
		}
		if (rrfWeightLexical == 0 && rrfWeightVector == 0) {
			// Both zero scores every candidate 0, so the result order would be arbitrary rather than empty -
			// the failure mode is a plausible-looking ranking with no meaning, which is worse than an error.
			errors.add("rrfWeightVector", "At least one fusion weight (LOOM_SEARCH_RRF_WEIGHT_LEXICAL, LOOM_SEARCH_RRF_WEIGHT_VECTOR) must be "
				+ "greater than zero, or hybrid ranking has nothing to rank by.");
		}
	}
}
