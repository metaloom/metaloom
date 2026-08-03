package io.metaloom.cortex.node.videogen;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link VideoGenNode}.
 *
 * <p>
 * The node calls the LTX-2 video sidecar (see {@code sidecars/ltx2-sidecar})
 * addressed by {@link #host} / {@link #port}. {@link #mode} selects text-to-video
 * ({@link VideoGenMode#GENERATE}) or image-to-video ({@link VideoGenMode#ANIMATE});
 * the generation {@link #prompt} is part of the pipeline node configuration and is
 * passed to the sidecar on each invocation.
 * </p>
 *
 * <p>
 * The sidecar snaps {@link #width}/{@link #height} to a multiple of 32 and
 * {@link #numFrames} to {@code k*8+1}, so those constraints do not need to be
 * enforced here; the defaults already satisfy them.
 * </p>
 */
public class VideoGenNodeOptions extends AbstractNodeOptions<VideoGenNodeOptions> {

	public static final String KEY = "videogen";

	// Every field carries an explicit order because the node re-documents the inherited timeoutMs and
	// pins it last: an ordered parameter anywhere sorts the unordered ones behind it.
	@ParamDoc(label = "Mode",
		description = "GENERATE ignores the source pixels and works from the prompt; ANIMATE feeds the source image in as the opening frame",
		order = 100)
	private VideoGenMode mode = VideoGenMode.GENERATE;

	@ParamDoc(label = "Prompt", description = "What to animate. Used unless the Prompt input port is wired", order = 110)
	private String prompt = "";

	@ParamDoc(label = "Negative Prompt",
		description = "What to steer away from. Left empty the sidecar applies its own default", order = 120)
	private String negativePrompt = "";

	@ParamDoc(label = "Sidecar Host", description = "Host of the video-generation sidecar", order = 130)
	private String host = "localhost";

	@ParamDoc(label = "Sidecar Port", description = "Port of the video-generation sidecar", min = "1", order = 140)
	private int port = 9220;

	@ParamDoc(label = "Generate Endpoint", description = "Sidecar path called in GENERATE mode", order = 150)
	private String generateEndpoint = "/generate";

	@ParamDoc(label = "Animate Endpoint", description = "Sidecar path called in ANIMATE mode", order = 160)
	private String animateEndpoint = "/animate";

	@ParamDoc(label = "Width (px)", description = "Width of the generated clip; snapped to a multiple of 32 by the sidecar",
		min = "1", order = 170)
	private int width = 768;

	@ParamDoc(label = "Height (px)", description = "Height of the generated clip; snapped to a multiple of 32 by the sidecar",
		min = "1", order = 180)
	private int height = 512;

	@ParamDoc(label = "Frames", description = "Number of frames; snapped to k*8+1 by the sidecar", min = "1", order = 190)
	private int numFrames = 49;

	@ParamDoc(label = "FPS", description = "Frame rate of the produced clip", min = "1", order = 200)
	private int fps = 24;

	@ParamDoc(label = "Steps", description = "Diffusion steps. More steps cost proportionally more time", min = "1", order = 210)
	private int steps = 40;

	@ParamDoc(label = "Guidance", description = "Prompt guidance scale", min = "0.0", step = "0.5", order = 220)
	private double guidance = 4.0;

	@ParamDoc(label = "Seed",
		description = "Fix the seed to make a run reproducible. Left empty the sidecar picks one per item", order = 230)
	private Integer seed = null;

	public VideoGenNodeOptions() {
		// Video generation is slower than image generation - one clip can offload a large
		// model per step, so give it a generous default wall clock.
		setTimeoutMs(1_800_000);
	}

	@Override
	protected VideoGenNodeOptions self() {
		return this;
	}

	public VideoGenMode getMode() {
		return mode;
	}

	public VideoGenNodeOptions setMode(VideoGenMode mode) {
		this.mode = mode;
		return this;
	}

	public String getPrompt() {
		return prompt;
	}

	public VideoGenNodeOptions setPrompt(String prompt) {
		this.prompt = prompt;
		return this;
	}

	public String getNegativePrompt() {
		return negativePrompt;
	}

	public VideoGenNodeOptions setNegativePrompt(String negativePrompt) {
		this.negativePrompt = negativePrompt;
		return this;
	}

	public String getHost() {
		return host;
	}

	public VideoGenNodeOptions setHost(String host) {
		this.host = host;
		return this;
	}

	public int getPort() {
		return port;
	}

	public VideoGenNodeOptions setPort(int port) {
		this.port = port;
		return this;
	}

	public String getGenerateEndpoint() {
		return generateEndpoint;
	}

	public VideoGenNodeOptions setGenerateEndpoint(String generateEndpoint) {
		this.generateEndpoint = generateEndpoint;
		return this;
	}

	public String getAnimateEndpoint() {
		return animateEndpoint;
	}

	public VideoGenNodeOptions setAnimateEndpoint(String animateEndpoint) {
		this.animateEndpoint = animateEndpoint;
		return this;
	}

	public int getWidth() {
		return width;
	}

	public VideoGenNodeOptions setWidth(int width) {
		this.width = width;
		return this;
	}

	public int getHeight() {
		return height;
	}

	public VideoGenNodeOptions setHeight(int height) {
		this.height = height;
		return this;
	}

	public int getNumFrames() {
		return numFrames;
	}

	public VideoGenNodeOptions setNumFrames(int numFrames) {
		this.numFrames = numFrames;
		return this;
	}

	public int getFps() {
		return fps;
	}

	public VideoGenNodeOptions setFps(int fps) {
		this.fps = fps;
		return this;
	}

	public int getSteps() {
		return steps;
	}

	public VideoGenNodeOptions setSteps(int steps) {
		this.steps = steps;
		return this;
	}

	public double getGuidance() {
		return guidance;
	}

	public VideoGenNodeOptions setGuidance(double guidance) {
		this.guidance = guidance;
		return this;
	}

	public Integer getSeed() {
		return seed;
	}

	public VideoGenNodeOptions setSeed(Integer seed) {
		this.seed = seed;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (mode == null) {
			errors.add("mode must not be null");
		}
		if (prompt == null || prompt.isBlank()) {
			errors.add("prompt must not be empty");
		}
		if (host == null || host.isBlank()) {
			errors.add("host must not be empty");
		}
		if (port <= 0) {
			errors.add("port must be positive, got " + port);
		}
		if (width <= 0) {
			errors.add("width must be positive, got " + width);
		}
		if (height <= 0) {
			errors.add("height must be positive, got " + height);
		}
		if (numFrames <= 0) {
			errors.add("numFrames must be positive, got " + numFrames);
		}
		if (fps <= 0) {
			errors.add("fps must be positive, got " + fps);
		}
		if (steps <= 0) {
			errors.add("steps must be positive, got " + steps);
		}
		if (guidance < 0) {
			errors.add("guidance must be non-negative, got " + guidance);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
