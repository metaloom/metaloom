package io.metaloom.cortex.node.voiceprint;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link VoiceprintNode}.
 *
 * <p>
 * {@link #voiceprintHost} / {@link #voiceprintPort} address the FastAPI {@code /v1/voiceprints} sidecar ({@code sidecars/voiceprint}).
 * {@link #model} picks the backend inside the sidecar ({@code redimnet2}, {@code wespeaker}, {@code speechbrain}, or any full model id the
 * sidecar's {@code /v1/models} lists); empty leaves it to the sidecar's default, which is the ReDimNet2-B6 VoxCeleb2 checkpoint. The windowing and
 * clustering knobs are forwarded as-is; a zero {@link #clusterThreshold} means "the backend's own threshold", because the right cosine cut is a
 * property of the model and the sidecar knows it.
 * </p>
 */
public class VoiceprintNodeOptions extends AbstractNodeOptions<VoiceprintNodeOptions> {

	public static final String KEY = "voiceprint";

	/** The {@code embedding.type} and {@code cluster.type} the node writes. Free text on the server; one string here so every writer agrees. */
	public static final String EMBEDDING_TYPE = "voiceprint";

	public static final String CLUSTER_TYPE = "speaker";

	@ParamDoc(label = "Sidecar Host", description = "Host of the /v1/voiceprints sidecar", order = 100)
	private String voiceprintHost = "localhost";

	/** 9150 - the ASR sidecar owns 9140. */
	@ParamDoc(label = "Sidecar Port", description = "Port of the /v1/voiceprints sidecar", min = "1", order = 110)
	private int voiceprintPort = 9150;

	@ParamDoc(label = "Model", description = "Sidecar backend: redimnet2, wespeaker or speechbrain (or a full model id). Empty uses the sidecar's default", order = 200)
	private String model = "";

	@ParamDoc(label = "Window (s)", description = "Length of the speech window one voiceprint covers", min = "0.5", max = "30", step = "0.5", order = 300)
	private double windowSeconds = 3.0;

	@ParamDoc(label = "Hop (s)", description = "Step between windows; at most the window length", min = "0.1", max = "30", step = "0.5", order = 310)
	private double hopSeconds = 1.5;

	@ParamDoc(label = "Min Speech (s)", description = "Shortest stretch of speech that still gets a voiceprint", min = "0.1", max = "30", step = "0.1", order = 320)
	private double minSpeechSeconds = 1.0;

	@ParamDoc(label = "Cluster Threshold", description = "Cosine similarity at which two windows are the same speaker; 0 uses the backend's calibrated default", min = "-1", max = "1", step = "0.05", order = 400)
	private double clusterThreshold = 0.0;

	@ParamDoc(label = "Max Speakers", description = "Upper bound on the speakers found per item; 0 = unlimited", min = "0", order = 410)
	private int maxSpeakers = 0;

	@ParamDoc(label = "Persist Windows", description = "Store one embedding per speech window (true) or only one per speaker (false)", order = 500)
	private boolean persistWindows = true;

	@ParamDoc(label = "ffmpeg Path", description = "The ffmpeg binary used to demux a video's audio track before upload", order = 900)
	private String ffmpegPath = "ffmpeg";

	@Override
	protected VoiceprintNodeOptions self() {
		return this;
	}

	public String getVoiceprintHost() {
		return voiceprintHost;
	}

	public VoiceprintNodeOptions setVoiceprintHost(String voiceprintHost) {
		this.voiceprintHost = voiceprintHost;
		return this;
	}

	public int getVoiceprintPort() {
		return voiceprintPort;
	}

	public VoiceprintNodeOptions setVoiceprintPort(int voiceprintPort) {
		this.voiceprintPort = voiceprintPort;
		return this;
	}

	public String getModel() {
		return model;
	}

	public VoiceprintNodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public double getWindowSeconds() {
		return windowSeconds;
	}

	public VoiceprintNodeOptions setWindowSeconds(double windowSeconds) {
		this.windowSeconds = windowSeconds;
		return this;
	}

	public double getHopSeconds() {
		return hopSeconds;
	}

	public VoiceprintNodeOptions setHopSeconds(double hopSeconds) {
		this.hopSeconds = hopSeconds;
		return this;
	}

	public double getMinSpeechSeconds() {
		return minSpeechSeconds;
	}

	public VoiceprintNodeOptions setMinSpeechSeconds(double minSpeechSeconds) {
		this.minSpeechSeconds = minSpeechSeconds;
		return this;
	}

	public double getClusterThreshold() {
		return clusterThreshold;
	}

	public VoiceprintNodeOptions setClusterThreshold(double clusterThreshold) {
		this.clusterThreshold = clusterThreshold;
		return this;
	}

	public int getMaxSpeakers() {
		return maxSpeakers;
	}

	public VoiceprintNodeOptions setMaxSpeakers(int maxSpeakers) {
		this.maxSpeakers = maxSpeakers;
		return this;
	}

	public boolean isPersistWindows() {
		return persistWindows;
	}

	public VoiceprintNodeOptions setPersistWindows(boolean persistWindows) {
		this.persistWindows = persistWindows;
		return this;
	}

	public String getFfmpegPath() {
		return ffmpegPath;
	}

	public VoiceprintNodeOptions setFfmpegPath(String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
		return this;
	}

	/**
	 * The model the sidecar is asked for, or null to let it use its default.
	 */
	public String resolvedModel() {
		return model == null || model.isBlank() ? null : model.trim();
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (voiceprintHost == null || voiceprintHost.isBlank()) {
			errors.add("voiceprintHost must not be empty");
		}
		if (voiceprintPort <= 0) {
			errors.add("voiceprintPort must be positive, got " + voiceprintPort);
		}
		if (windowSeconds <= 0) {
			errors.add("windowSeconds must be positive, got " + windowSeconds);
		}
		if (hopSeconds <= 0) {
			errors.add("hopSeconds must be positive, got " + hopSeconds);
		} else if (hopSeconds > windowSeconds) {
			errors.add("hopSeconds must not exceed windowSeconds, got " + hopSeconds + " > " + windowSeconds);
		}
		if (minSpeechSeconds <= 0) {
			errors.add("minSpeechSeconds must be positive, got " + minSpeechSeconds);
		}
		if (clusterThreshold < -1.0 || clusterThreshold > 1.0) {
			errors.add("clusterThreshold must be a cosine similarity in [-1, 1], got " + clusterThreshold);
		}
		if (maxSpeakers < 0) {
			errors.add("maxSpeakers must be non-negative, got " + maxSpeakers);
		}
		if (ffmpegPath == null || ffmpegPath.isBlank()) {
			errors.add("ffmpegPath must not be empty");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
