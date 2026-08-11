package io.metaloom.loom.api.options;

/**
 * Options which control the perceptual fingerprint similarity index (see spec/loom/SEARCH_LUCENE.md).
 *
 * <p>
 * Like search, similarity is a capability, not a dependency: when it is disabled or the index directory is unusable, Loom still boots and every other
 * route keeps working. The similarity routes then reject requests with a reason rather than silently answering "no duplicates" - an empty result and a
 * broken index must never look the same to a caller.
 * </p>
 *
 * <p>
 * Disabled by default: the index is only useful once videos have been fingerprinted, and standing it up costs disk.
 * </p>
 */
public class SimilarityOptions implements Option {

	/** The algorithm {@code FingerprintNode} writes to {@code asset_fingerprint_comp}. */
	public static final String DEFAULT_ALGORITHM = "metaloom-multisector-v1";

	/** Matches video4j's {@code QueryResultFactory.DEFAULT_SCORE_THRESHOLD}. */
	public static final float DEFAULT_SCORE_THRESHOLD = 0.10f;

	public static final int DEFAULT_TOPK = 10;

	@EnvironmentVariable(name = "LOOM_SIMILARITY_ENABLED", description = "Master switch for the fingerprint similarity index. When off the similar-assets routes reject requests.")
	private boolean enabled = false;

	@EnvironmentVariable(name = "LOOM_SIMILARITY_INDEX_PATH", description = "Directory holding the on-disk Lucene k-NN index. Rebuildable from asset_fingerprint_comp at any time.")
	private String indexPath = "similarity-index";

	@EnvironmentVariable(name = "LOOM_SIMILARITY_ALGORITHM", description = "Which asset_fingerprint_comp.algorithm participates in the index.")
	private String algorithm = DEFAULT_ALGORITHM;

	@EnvironmentVariable(name = "LOOM_SIMILARITY_SCORE_THRESHOLD", description = "Default k-NN score floor. Hits below this are dropped; overridable per request.")
	private float scoreThreshold = DEFAULT_SCORE_THRESHOLD;

	@EnvironmentVariable(name = "LOOM_SIMILARITY_TOPK", description = "Default number of neighbours returned per query; overridable per request.")
	private int topK = DEFAULT_TOPK;

	public boolean isEnabled() {
		return enabled;
	}

	public SimilarityOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getIndexPath() {
		return indexPath;
	}

	public SimilarityOptions setIndexPath(String indexPath) {
		this.indexPath = indexPath;
		return this;
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public SimilarityOptions setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	public float getScoreThreshold() {
		return scoreThreshold;
	}

	public SimilarityOptions setScoreThreshold(float scoreThreshold) {
		this.scoreThreshold = scoreThreshold;
		return this;
	}

	public int getTopK() {
		return topK;
	}

	public SimilarityOptions setTopK(int topK) {
		this.topK = topK;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (!enabled) {
			return;
		}
		if (indexPath == null || indexPath.isBlank()) {
			errors.add("indexPath", "The similarity index path (LOOM_SIMILARITY_INDEX_PATH) must be set when similarity is enabled.");
		}
		if (algorithm == null || algorithm.isBlank()) {
			errors.add("algorithm", "The similarity algorithm (LOOM_SIMILARITY_ALGORITHM) must be set.");
		}
		if (scoreThreshold < 0f) {
			errors.add("scoreThreshold", "The similarity score threshold (LOOM_SIMILARITY_SCORE_THRESHOLD) must not be negative.");
		}
		errors.min("topK", topK, 1);
	}
}
