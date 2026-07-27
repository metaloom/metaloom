package io.metaloom.cortex.node.sentiment;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link SentimentNode}.
 *
 * <p>
 * The node scores text produced by an upstream node. {@link #textSources} is an
 * <em>ordered</em> list of {@code nodeId:outputKey} pairs; the first one that
 * yields non-blank text wins, and its output key becomes the {@code variant} of
 * the persisted component so several text sources can coexist on one asset.
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

	/** Default text sources, most specific first. Whisper is absent on purpose - {@code whisper_result} is transcript JSON, not plain text. */
	public static final List<String> DEFAULT_TEXT_SOURCES = List.of(
		"tika:tika_content",
		"ocr:ocr_text",
		"captioning:caption_result",
		"vlm:vlm_result",
		"llm:llm_result");

	public static final String LANGUAGE_AUTO = "auto";

	private String sentimentHost = "localhost";

	/** 9110 - the TTS sidecar already owns 9100. */
	private int sentimentPort = 9110;

	private String language = LANGUAGE_AUTO;

	private String modelDe;
	private String modelEn;

	private List<String> textSources = new ArrayList<>(DEFAULT_TEXT_SOURCES);

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

	public List<String> getTextSources() {
		return textSources;
	}

	public SentimentNodeOptions setTextSources(List<String> textSources) {
		this.textSources = textSources;
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
		if (textSources == null || textSources.isEmpty()) {
			errors.add("textSources must not be empty");
		} else {
			for (String source : textSources) {
				if (source == null || !source.contains(":") || source.startsWith(":") || source.endsWith(":")) {
					errors.add("textSources entry must have the form 'nodeId:outputKey', got '" + source + "'");
				}
			}
		}
		if (maxChars <= 0) {
			errors.add("maxChars must be positive, got " + maxChars);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
