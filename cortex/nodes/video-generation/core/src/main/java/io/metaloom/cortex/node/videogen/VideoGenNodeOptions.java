package io.metaloom.cortex.node.videogen;

import java.util.ArrayList;
import java.util.List;

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

	private VideoGenMode mode = VideoGenMode.GENERATE;
	private String prompt = "";
	private String negativePrompt = "";

	private String host = "localhost";
	private int port = 9220;
	private String generateEndpoint = "/generate";
	private String animateEndpoint = "/animate";

	private int width = 768;
	private int height = 512;
	private int numFrames = 49;
	private int fps = 24;
	private int steps = 40;
	private double guidance = 4.0;
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
