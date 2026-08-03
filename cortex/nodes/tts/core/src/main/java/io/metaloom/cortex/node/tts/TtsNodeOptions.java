package io.metaloom.cortex.node.tts;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link TtsNode}.
 *
 * <p>
 * The node speaks whatever text is wired into its {@code text} input port; there is
 * no option for choosing a source any more. {@link #language} routes
 * the synthesis inside the sidecar: {@code de} uses Orpheus/Kartoffel, {@code en}
 * uses Kokoro. {@link #ttsHost} / {@link #ttsPort} address the FastAPI
 * {@code /v1/tts} sidecar.
 * </p>
 */
public class TtsNodeOptions extends AbstractNodeOptions<TtsNodeOptions> {

	public static final String KEY = "tts";

	@ParamDoc(label = "Sidecar Host", description = "Host of the /v1/tts sidecar", order = 100)
	private String ttsHost = "localhost";

	@ParamDoc(label = "Sidecar Port", description = "Port of the /v1/tts sidecar", min = "1", order = 110)
	private int ttsPort = 9100;

	@ParamDoc(label = "Language",
		description = "Selects the synthesis stack inside the sidecar: 'de' uses Orpheus/Kartoffel, 'en' uses Kokoro", order = 120)
	private String language = "de";

	@ParamDoc(label = "Voice",
		description = "Voice id offered by the selected stack. A voice from the other language is not substituted", order = 130)
	private String voice = "Jakob";

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
		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
