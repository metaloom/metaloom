package io.metaloom.cortex.node.imagegen;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link ImageGenNode}.
 *
 * <p>
 * The node calls the image-generation sidecar (see {@code sidecars/ideogram-sidecar})
 * addressed by {@link #host} / {@link #port}. {@link #mode} selects text-to-image
 * ({@link ImageGenMode#GENERATE}) or image-to-image ({@link ImageGenMode#REMIX}); the
 * generation {@link #prompt} is part of the pipeline node configuration and is passed
 * to the sidecar on each invocation.
 * </p>
 */
public class ImageGenNodeOptions extends AbstractNodeOptions<ImageGenNodeOptions> {

	public static final String KEY = "imagegen";

	private ImageGenMode mode = ImageGenMode.GENERATE;
	private String prompt = "";

	private String host = "localhost";
	private int port = 9200;
	private String generateEndpoint = "/generate";
	private String remixEndpoint = "/remix";

	private int width = 1024;
	private int height = 1024;
	private double strength = 0.6;
	private Integer seed = null;
	private int steps = 30;

	public ImageGenNodeOptions() {
		setTimeoutMs(120_000);
	}

	@Override
	protected ImageGenNodeOptions self() {
		return this;
	}

	public ImageGenMode getMode() {
		return mode;
	}

	public ImageGenNodeOptions setMode(ImageGenMode mode) {
		this.mode = mode;
		return this;
	}

	public String getPrompt() {
		return prompt;
	}

	public ImageGenNodeOptions setPrompt(String prompt) {
		this.prompt = prompt;
		return this;
	}

	public String getHost() {
		return host;
	}

	public ImageGenNodeOptions setHost(String host) {
		this.host = host;
		return this;
	}

	public int getPort() {
		return port;
	}

	public ImageGenNodeOptions setPort(int port) {
		this.port = port;
		return this;
	}

	public String getGenerateEndpoint() {
		return generateEndpoint;
	}

	public ImageGenNodeOptions setGenerateEndpoint(String generateEndpoint) {
		this.generateEndpoint = generateEndpoint;
		return this;
	}

	public String getRemixEndpoint() {
		return remixEndpoint;
	}

	public ImageGenNodeOptions setRemixEndpoint(String remixEndpoint) {
		this.remixEndpoint = remixEndpoint;
		return this;
	}

	public int getWidth() {
		return width;
	}

	public ImageGenNodeOptions setWidth(int width) {
		this.width = width;
		return this;
	}

	public int getHeight() {
		return height;
	}

	public ImageGenNodeOptions setHeight(int height) {
		this.height = height;
		return this;
	}

	public double getStrength() {
		return strength;
	}

	public ImageGenNodeOptions setStrength(double strength) {
		this.strength = strength;
		return this;
	}

	public Integer getSeed() {
		return seed;
	}

	public ImageGenNodeOptions setSeed(Integer seed) {
		this.seed = seed;
		return this;
	}

	public int getSteps() {
		return steps;
	}

	public ImageGenNodeOptions setSteps(int steps) {
		this.steps = steps;
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
		if (steps <= 0) {
			errors.add("steps must be positive, got " + steps);
		}
		if (strength <= 0 || strength > 1) {
			errors.add("strength must be in (0, 1], got " + strength);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
