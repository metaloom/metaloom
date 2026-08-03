package io.metaloom.cortex.node.dedup;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the fingerprint-dedup discovery node.
 */
public class FingerprintDedupDiscoverOptions extends AbstractNodeOptions<FingerprintDedupDiscoverOptions> {

	public static final String DEFAULT_ALGORITHM = "metaloom-multisector-v1";
	public static final float DEFAULT_SCORE_THRESHOLD = 0.10f;
	public static final int DEFAULT_TOPK = 10;

	@ParamDoc(label = "Algorithm", order = 40,
		description = "Fingerprint algorithm whose vectors are compared. Must match the algorithm the "
			+ "upstream fingerprint node produced, or nothing will be found similar")
	private String algorithm = DEFAULT_ALGORITHM;

	@ParamDoc(label = "Score Threshold", min = "0.0", order = 50,
		description = "Maximum distance at which two fingerprints count as near-duplicates. Lower is "
			+ "stricter; 0 matches only identical fingerprints")
	private float scoreThreshold = DEFAULT_SCORE_THRESHOLD;

	@ParamDoc(label = "Neighbours", min = "1", order = 60,
		description = "How many nearest neighbours to examine per item before deciding a group")
	private int topK = DEFAULT_TOPK;

	/** Whether a group may be formed when no member is fully complete. Off by default (never discard where completeness is unknown). */
	@ParamDoc(label = "Allow Partial Groups", order = 70,
		description = "Form a group even when no member is known to be complete. Off by default: never "
			+ "propose discarding a file where completeness is unknown")
	private boolean allowPartial = false;

	/** Abort a candidate group if any DUP is larger than the KEEP (never propose discarding the larger/more-complete file). */
	@ParamDoc(label = "Abort On Larger Duplicate", order = 80,
		description = "Abandon a candidate group if any duplicate is larger than the file being kept, so "
			+ "the more complete file is never the one discarded")
	private boolean abortOnLargerDup = true;

	public String getAlgorithm() {
		return algorithm;
	}

	public FingerprintDedupDiscoverOptions setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
		return this;
	}

	public float getScoreThreshold() {
		return scoreThreshold;
	}

	public FingerprintDedupDiscoverOptions setScoreThreshold(float scoreThreshold) {
		this.scoreThreshold = scoreThreshold;
		return this;
	}

	public int getTopK() {
		return topK;
	}

	public FingerprintDedupDiscoverOptions setTopK(int topK) {
		this.topK = topK;
		return this;
	}

	public boolean isAllowPartial() {
		return allowPartial;
	}

	public FingerprintDedupDiscoverOptions setAllowPartial(boolean allowPartial) {
		this.allowPartial = allowPartial;
		return this;
	}

	public boolean isAbortOnLargerDup() {
		return abortOnLargerDup;
	}

	public FingerprintDedupDiscoverOptions setAbortOnLargerDup(boolean abortOnLargerDup) {
		this.abortOnLargerDup = abortOnLargerDup;
		return this;
	}

	@Override
	protected FingerprintDedupDiscoverOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());
		if (algorithm == null || algorithm.isBlank()) {
			errors.add("algorithm must not be empty");
		}
		if (topK <= 0) {
			errors.add("topK must be greater than 0");
		}
		if (scoreThreshold < 0f) {
			errors.add("scoreThreshold must not be negative");
		}
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
