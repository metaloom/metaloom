package io.metaloom.cortex.node.sentiment;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link SentimentNode}.
 *
 * <p>
 * The node scores whatever text is wired into its {@code text} input port; there is
 * no option for choosing a source any more.
 * </p>
 *
 * <p>
 * {@link #sentimentHost} / {@link #sentimentPort} address the FastAPI
 * {@code /v1/sentiment} sidecar ({@code sidecars/sentiment}). {@link #language}
 * routes the model selection inside the sidecar: {@code de} uses
 * german-sentiment-bert, {@code en} uses twitter-roberta, {@code auto} (the
 * default) lets the sidecar detect it. {@link #modelDe} / {@link #modelEn}
 * override the sidecar's own defaults per request when set.
 * </p>
 */
public class SentimentNodeOptions extends AbstractNodeOptions<SentimentNodeOptions> {

	public static final String KEY = "sentiment";

	public static final String LANGUAGE_AUTO = "auto";

	private String sentimentHost = "localhost";

	/** 9110 - the TTS sidecar already owns 9100. */
	private int sentimentPort = 9110;

	private String language = LANGUAGE_AUTO;

	private String modelDe;
	private String modelEn;

	/** Upper bound on the text handed to the sidecar. Longer text is truncated before the request. */
	private int maxChars = 200_000;

	@Override
	protected SentimentNodeOptions self() {
		return this;
	}

	public String getSentimentHost() {
		return sentimentHost;
	}

	public SentimentNodeOptions setSentimentHost(String sentimentHost) {
		this.sentimentHost = sentimentHost;
		return this;
	}

	public int getSentimentPort() {
		return sentimentPort;
	}

	public SentimentNodeOptions setSentimentPort(int sentimentPort) {
		this.sentimentPort = sentimentPort;
		return this;
	}

	public String getLanguage() {
		return language;
	}

	public SentimentNodeOptions setLanguage(String language) {
		this.language = language;
		return this;
	}

	public String getModelDe() {
		return modelDe;
	}

	public SentimentNodeOptions setModelDe(String modelDe) {
		this.modelDe = modelDe;
		return this;
	}

	public String getModelEn() {
		return modelEn;
	}

	public SentimentNodeOptions setModelEn(String modelEn) {
		this.modelEn = modelEn;
		return this;
	}

	public int getMaxChars() {
		return maxChars;
	}

	public SentimentNodeOptions setMaxChars(int maxChars) {
		this.maxChars = maxChars;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (sentimentHost == null || sentimentHost.isBlank()) {
			errors.add("sentimentHost must not be empty");
		}
		if (sentimentPort <= 0) {
			errors.add("sentimentPort must be positive, got " + sentimentPort);
		}
		if (language == null || language.isBlank()) {
			errors.add("language must not be empty");
		}
		if (maxChars <= 0) {
			errors.add("maxChars must be positive, got " + maxChars);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
