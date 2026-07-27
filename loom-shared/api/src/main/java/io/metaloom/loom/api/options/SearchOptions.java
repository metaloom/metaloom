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
	}
}
