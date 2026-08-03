package io.metaloom.cortex.node.sentiment;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
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

	@ParamDoc(label = "Sidecar Host", description = "Host of the /v1/sentiment sidecar")
	private String sentimentHost = "localhost";

	/** 9110 - the TTS sidecar already owns 9100. */
	@ParamDoc(label = "Sidecar Port", description = "Port of the /v1/sentiment sidecar", min = "1")
	private int sentimentPort = 9110;

	@ParamDoc(label = "Language", description = "'de', 'en', or 'auto' to let the sidecar detect it")
	private String language = LANGUAGE_AUTO;

	@ParamDoc(label = "German Model", description = "Override the sidecar's German checkpoint")
	private String modelDe;

	@ParamDoc(label = "English Model", description = "Override the sidecar's English checkpoint")
	private String modelEn;

	/** Upper bound on the text handed to the sidecar. Longer text is truncated before the request. */
	@ParamDoc(label = "Max Characters", description = "Upper bound on the text sent to the sidecar", min = "1")
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
