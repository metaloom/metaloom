package io.metaloom.cortex.node.tts;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link TtsNode}.
 *
 * <p>
 * The node speaks text produced by an upstream node. {@link #sourceNodeId} /
 * {@link #sourceOutputKey} select which upstream output supplies the text (by
 * default the {@code llm} node's {@code llm_result}). {@link #language} routes
 * the synthesis inside the sidecar: {@code de} uses Orpheus/Kartoffel, {@code en}
 * uses Kokoro. {@link #ttsHost} / {@link #ttsPort} address the FastAPI
 * {@code /v1/tts} sidecar.
 * </p>
 */
public class TtsNodeOptions extends AbstractNodeOptions<TtsNodeOptions> {

	public static final String KEY = "tts";

	private String ttsHost = "localhost";
	private int ttsPort = 9100;

	private String language = "de";
	private String voice = "Jakob";

	private String sourceNodeId = "llm";
	private String sourceOutputKey = "llm_result";

	@Override
	protected TtsNodeOptions self() {
		return this;
	}

	public String getTtsHost() {
		return ttsHost;
	}

	public TtsNodeOptions setTtsHost(String ttsHost) {
		this.ttsHost = ttsHost;
		return this;
	}

	public int getTtsPort() {
		return ttsPort;
	}

	public TtsNodeOptions setTtsPort(int ttsPort) {
		this.ttsPort = ttsPort;
		return this;
	}

	public String getLanguage() {
		return language;
	}

	public TtsNodeOptions setLanguage(String language) {
		this.language = language;
		return this;
	}

	public String getVoice() {
		return voice;
	}

	public TtsNodeOptions setVoice(String voice) {
		this.voice = voice;
		return this;
	}

	public String getSourceNodeId() {
		return sourceNodeId;
	}

	public TtsNodeOptions setSourceNodeId(String sourceNodeId) {
		this.sourceNodeId = sourceNodeId;
		return this;
	}

	public String getSourceOutputKey() {
		return sourceOutputKey;
	}

	public TtsNodeOptions setSourceOutputKey(String sourceOutputKey) {
		this.sourceOutputKey = sourceOutputKey;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (ttsHost == null || ttsHost.isBlank()) {
			errors.add("ttsHost must not be empty");
		}
		if (ttsPort <= 0) {
			errors.add("ttsPort must be positive, got " + ttsPort);
		}
		if (language == null || language.isBlank()) {
			errors.add("language must not be empty");
		}
		if (sourceNodeId == null || sourceNodeId.isBlank()) {
			errors.add("sourceNodeId must not be empty");
		}
		if (sourceOutputKey == null || sourceOutputKey.isBlank()) {
			errors.add("sourceOutputKey must not be empty");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
