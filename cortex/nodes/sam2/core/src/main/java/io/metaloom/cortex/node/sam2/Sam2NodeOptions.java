package io.metaloom.cortex.node.sam2;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link Sam2Node}.
 *
 * <p>
 * {@link #sam2Host} / {@link #sam2Port} address the FastAPI sidecar ({@code sidecars/sam2}).
 * {@link #mode} selects which of SAM 2's three jobs this instance does — see {@link Sam2Mode}.
 * {@link #model} overrides the sidecar's own checkpoint.
 * </p>
 *
 * <p>
 * {@link #maxDim} bounds inference cost by capping the longest side sent to the sidecar. It also
 * determines the size of every produced mask, which is why the emitted metadata reports the mask
 * dimensions separately from the source image's — the same trap the depthmap node documents.
 * </p>
 */
public class Sam2NodeOptions extends AbstractNodeOptions<Sam2NodeOptions> {

	public static final String KEY = "sam2";

	@ParamDoc(label = "Sidecar Host", description = "Host of the SAM 2 sidecar", order = 100)
	private String sam2Host = "localhost";

	/** 9130 - 9100 is the TTS sidecar, 9110 sentiment, 9120 depth. */
	@ParamDoc(label = "Sidecar Port", description = "Port of the SAM 2 sidecar", min = "1", order = 110)
	private int sam2Port = 9130;

	@ParamDoc(label = "Mode",
		description = "AUTOMATIC segments everything; PROMPTED cuts out the boxes an upstream detector found; "
			+ "TRACK follows them through a video",
		order = 115)
	private Sam2Mode mode = Sam2Mode.AUTOMATIC;

	/** Explicit checkpoint override, or null to use the sidecar's default. */
	@ParamDoc(label = "Model Override", description = "Checkpoint to use instead of the sidecar's default", order = 120)
	private String model;

	/** Longest side sent to the sidecar. The sidecar clamps this to its own SAM2_MAX_DIM. */
	@ParamDoc(label = "Max Dimension",
		description = "Longest side sent to the sidecar; also the size of every produced mask",
		min = "64", max = "4096", step = "64", order = 130)
	private int maxDim = 1024;

	@ParamDoc(label = "Points Per Side",
		description = "AUTOMATIC only: the sampling grid. 32 means 1024 forward passes per image",
		min = "4", max = "64", step = "4", order = 140)
	private int pointsPerSide = 32;

	@ParamDoc(label = "Predicted IoU Threshold",
		description = "AUTOMATIC only: drop masks the model rates below this",
		min = "0.0", max = "1.0", step = "0.05", order = 150)
	private double predIouThresh = 0.8d;

	@ParamDoc(label = "Stability Threshold",
		description = "AUTOMATIC only: drop masks whose boundary moves under a threshold shift",
		min = "0.0", max = "1.0", step = "0.01", order = 160)
	private double stabilityScoreThresh = 0.95d;

	@ParamDoc(label = "Min Mask Area (px)",
		description = "Discard masks smaller than this, measured on the downscaled image",
		min = "0", order = 170)
	private int minMaskArea = 256;

	@ParamDoc(label = "Max Masks",
		description = "Stop after this many masks for one item; hitting it reports CAPPED on the flag port",
		min = "1", order = 180)
	private int maxMasks = 64;

	/**
	 * ⚠️ Interacts with {@link #maxMasks}: three candidates per box are produced <em>before</em> the cap
	 * applies, so a permissive box set can be truncated purely by turning this on. Documented rather
	 * than validated - both settings are individually legal.
	 */
	@ParamDoc(label = "Multiple Masks Per Box",
		description = "PROMPTED only: return SAM 2's three candidate masks per box instead of the best one. "
			+ "Counts against Max Masks three times over",
		order = 190)
	private boolean multimask = false;

	@ParamDoc(label = "Video Chop Rate",
		description = "Sample every Nth video frame - roughly one per second of typical footage",
		min = "1", order = 200)
	private int videoChopRate = 25;

	@ParamDoc(label = "Max Frames",
		description = "Never send more than this many frames to the sidecar; hitting it reports CAPPED",
		min = "1", max = "512", order = 210)
	private int maxFrames = 64;

	@ParamDoc(label = "Annotation Frame",
		description = "TRACK and AUTOMATIC-on-video: which sampled frame the prompts are placed on",
		min = "0", order = 220)
	private int trackFrame = 0;

	@ParamDoc(label = "Write Overlay",
		description = "Also write the source frame with every mask tinted over it - the picture of what was segmented",
		order = 230)
	private boolean emitOverlay = true;

	public Sam2NodeOptions() {
		// Segment-everything at pointsPerSide=32 is 1024 forward passes, and a 64-frame propagation
		// runs the memory-attention stack over every one of them. Minutes, not seconds - depthmap's
		// 120s would time out a perfectly healthy request.
		setTimeoutMs(300_000);
	}

	@Override
	protected Sam2NodeOptions self() {
		return this;
	}

	public String getSam2Host() {
		return sam2Host;
	}

	public Sam2NodeOptions setSam2Host(String sam2Host) {
		this.sam2Host = sam2Host;
		return this;
	}

	public int getSam2Port() {
		return sam2Port;
	}

	public Sam2NodeOptions setSam2Port(int sam2Port) {
		this.sam2Port = sam2Port;
		return this;
	}

	public Sam2Mode getMode() {
		return mode;
	}

	public Sam2NodeOptions setMode(Sam2Mode mode) {
		this.mode = mode;
		return this;
	}

	public String getModel() {
		return model;
	}

	public Sam2NodeOptions setModel(String model) {
		this.model = model;
		return this;
	}

	public int getMaxDim() {
		return maxDim;
	}

	public Sam2NodeOptions setMaxDim(int maxDim) {
		this.maxDim = maxDim;
		return this;
	}

	public int getPointsPerSide() {
		return pointsPerSide;
	}

	public Sam2NodeOptions setPointsPerSide(int pointsPerSide) {
		this.pointsPerSide = pointsPerSide;
		return this;
	}

	public double getPredIouThresh() {
		return predIouThresh;
	}

	public Sam2NodeOptions setPredIouThresh(double predIouThresh) {
		this.predIouThresh = predIouThresh;
		return this;
	}

	public double getStabilityScoreThresh() {
		return stabilityScoreThresh;
	}

	public Sam2NodeOptions setStabilityScoreThresh(double stabilityScoreThresh) {
		this.stabilityScoreThresh = stabilityScoreThresh;
		return this;
	}

	public int getMinMaskArea() {
		return minMaskArea;
	}

	public Sam2NodeOptions setMinMaskArea(int minMaskArea) {
		this.minMaskArea = minMaskArea;
		return this;
	}

	public int getMaxMasks() {
		return maxMasks;
	}

	public Sam2NodeOptions setMaxMasks(int maxMasks) {
		this.maxMasks = maxMasks;
		return this;
	}

	public boolean isMultimask() {
		return multimask;
	}

	public Sam2NodeOptions setMultimask(boolean multimask) {
		this.multimask = multimask;
		return this;
	}

	public int getVideoChopRate() {
		return videoChopRate;
	}

	public Sam2NodeOptions setVideoChopRate(int videoChopRate) {
		this.videoChopRate = videoChopRate;
		return this;
	}

	public int getMaxFrames() {
		return maxFrames;
	}

	public Sam2NodeOptions setMaxFrames(int maxFrames) {
		this.maxFrames = maxFrames;
		return this;
	}

	public int getTrackFrame() {
		return trackFrame;
	}

	public Sam2NodeOptions setTrackFrame(int trackFrame) {
		this.trackFrame = trackFrame;
		return this;
	}

	public boolean isEmitOverlay() {
		return emitOverlay;
	}

	public Sam2NodeOptions setEmitOverlay(boolean emitOverlay) {
		this.emitOverlay = emitOverlay;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (sam2Host == null || sam2Host.isBlank()) {
			errors.add("sam2Host must not be empty");
		}
		if (sam2Port <= 0) {
			errors.add("sam2Port must be positive, got " + sam2Port);
		}
		if (mode == null) {
			errors.add("mode must not be null");
		}
		if (maxDim <= 0) {
			errors.add("maxDim must be positive, got " + maxDim);
		}
		if (pointsPerSide <= 0) {
			errors.add("pointsPerSide must be positive, got " + pointsPerSide);
		}
		if (predIouThresh < 0d || predIouThresh > 1d) {
			errors.add("predIouThresh must be within [0,1], got " + predIouThresh);
		}
		if (stabilityScoreThresh < 0d || stabilityScoreThresh > 1d) {
			errors.add("stabilityScoreThresh must be within [0,1], got " + stabilityScoreThresh);
		}
		if (minMaskArea < 0) {
			errors.add("minMaskArea must not be negative, got " + minMaskArea);
		}
		if (maxMasks <= 0) {
			errors.add("maxMasks must be positive, got " + maxMasks);
		}
		if (videoChopRate <= 0) {
			errors.add("videoChopRate must be positive, got " + videoChopRate);
		}
		if (maxFrames <= 0) {
			errors.add("maxFrames must be positive, got " + maxFrames);
		}
		if (trackFrame < 0) {
			errors.add("trackFrame must not be negative, got " + trackFrame);
		}
		if (getTimeoutMs() <= 0) {
			errors.add("timeoutMs must be positive, got " + getTimeoutMs());
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
