package io.metaloom.cortex.node.filter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Per-instance configuration for a {@link FilterNode}.
 *
 * <p>
 * These arrive from the <em>pipeline definition</em>, not from the worker's YAML — the node is a
 * {@code PipelineConfigurable}. Two filter nodes in one graph therefore route by different things,
 * which is the whole reason there is one kind rather than eight.
 * </p>
 */
public class FilterNodeOptions extends AbstractNodeOptions<FilterNodeOptions> {

	public static final String KEY = "filter";

	private FilterBy filterBy = FilterBy.LANGUAGE;

	/** The bucket rows, exactly as the editor's {@code PORT_LIST} widget writes them. */
	private JsonArray buckets = new JsonArray();

	private String model = "meta-llama/Llama-3.2-3B-Instruct";

	/** Base URL of an OpenAI-compatible backend (llama.cpp, vLLM, Ollama's /v1 endpoint, ...). */
	private String openaiUrl = "http://127.0.0.1:8080/v1";

	/**
	 * How much of the wired text to send. Language is decided by the first paragraph; sending a
	 * whole transcript would cost tokens per item for no better answer.
	 */
	private int maxTextChars = 2000;

	/** Classifications below this land in {@code other} instead. */
	private double minConfidence = 0;

	public FilterBy getFilterBy() {
		return filterBy;
	}

	public FilterNodeOptions setFilterBy(FilterBy filterBy) {
		this.filterBy = filterBy;
		return this;
	}

	public JsonArray getBuckets() {
		return buckets;
	}

	public FilterNodeOptions setBuckets(JsonArray buckets) {
		this.buckets = buckets;
		return this;
	}

	public String getModel() {
		return model;
	}

	public FilterNodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public String openaiUrl() {
		return openaiUrl;
	}

	public FilterNodeOptions setOpenaiUrl(String openaiUrl) {
		this.openaiUrl = openaiUrl;
		return this;
	}

	public int getMaxTextChars() {
		return maxTextChars;
	}

	public FilterNodeOptions setMaxTextChars(int maxTextChars) {
		this.maxTextChars = maxTextChars;
		return this;
	}

	public double getMinConfidence() {
		return minConfidence;
	}

	public FilterNodeOptions setMinConfidence(double minConfidence) {
		this.minConfidence = minConfidence;
		return this;
	}

	/**
	 * The configured buckets as value objects, malformed rows dropped.
	 *
	 * <p>
	 * Dropping rather than throwing matches {@code FilterPortResolver}: the resolver will not have
	 * produced a port for a malformed row either, so the two agree on which branches exist. A row
	 * that produced no port but did produce a bucket would be a branch nothing could ever be wired
	 * to.
	 * </p>
	 */
	public List<FilterBucket> buckets() {
		List<FilterBucket> parsed = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		if (buckets == null) {
			return parsed;
		}
		for (Object entry : buckets) {
			if (!(entry instanceof JsonObject json)) {
				continue;
			}
			FilterBucket bucket = FilterBucket.from(json);
			if (bucket.isValid() && seen.add(bucket.id())) {
				parsed.add(bucket);
			}
		}
		return parsed;
	}

	@Override
	protected FilterNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (filterBy == null) {
			errors.add("filterBy must be set");
		}
		if (openaiUrl == null || openaiUrl.isBlank()) {
			errors.add("openaiUrl must not be empty");
		}
		if (model == null || model.isBlank()) {
			errors.add("model must not be empty");
		}
		if (maxTextChars <= 0) {
			errors.add("maxTextChars must be greater than 0");
		}
		if (minConfidence < 0 || minConfidence > 1) {
			errors.add("minConfidence must be between 0 and 1");
		}

		// Buckets are reported rather than dropped here: silently ignoring a row the author typed is
		// right at resolve time (they are mid-edit) and wrong at validate time (they have saved).
		Set<String> seen = new LinkedHashSet<>();
		if (buckets != null) {
			for (Object entry : buckets) {
				if (!(entry instanceof JsonObject json)) {
					errors.add("every bucket must be an object");
					continue;
				}
				FilterBucket bucket = FilterBucket.from(json);
				if (bucket.id() == null) {
					errors.add("every bucket must have an id");
				} else if (FilterBucket.RESERVED.contains(bucket.id())) {
					errors.add("bucket id '" + bucket.id() + "' is reserved; it is one of the node's fixed ports");
				} else if (!bucket.id().matches(FilterBucket.ID_PATTERN)) {
					errors.add("bucket id '" + bucket.id() + "' is not a valid port id");
				} else if (!seen.add(bucket.id())) {
					errors.add("duplicate bucket id '" + bucket.id() + "'");
				}
			}
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
