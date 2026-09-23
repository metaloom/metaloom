package io.metaloom.cortex.node.imagegen;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
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

	// Every field carries an explicit order: the descriptor lists steps before seed while the fields are
	// declared the other way round, and the node re-documents the inherited timeoutMs after both.
	@ParamDoc(label = "Mode",
		description = "GENERATE ignores the source pixels and works from the prompt; REMIX feeds the source image in too",
		order = 100)
	private ImageGenMode mode = ImageGenMode.GENERATE;

	@ParamDoc(label = "Prompt", description = "What to draw. Used unless the Prompt input port is wired", order = 110)
	private String prompt = "";

	@ParamDoc(label = "Sidecar Host", description = "Host of the image-generation sidecar", order = 120)
	private String host = "localhost";

	@ParamDoc(label = "Sidecar Port", description = "Port of the image-generation sidecar", min = "1", order = 130)
	private int port = 9200;

	@ParamDoc(label = "Generate Endpoint", description = "Sidecar path called in GENERATE mode", order = 140)
	private String generateEndpoint = "/generate";

	@ParamDoc(label = "Remix Endpoint", description = "Sidecar path called in REMIX mode", order = 150)
	private String remixEndpoint = "/remix";

	@ParamDoc(label = "Width (px)", description = "Width of the generated image", min = "1", order = 160)
	private int width = 1024;

	@ParamDoc(label = "Height (px)", description = "Height of the generated image", min = "1", order = 170)
	private int height = 1024;

	@ParamDoc(label = "Remix Strength",
		description = "How far REMIX may depart from the source image; 1.0 keeps almost nothing of it",
		min = "0.01", max = "1.0", step = "0.05", order = 180)
	private double strength = 0.6;

	@ParamDoc(label = "Seed",
		description = "Fix the seed to make a run reproducible. Left empty the sidecar picks one per item", order = 200)
	private Integer seed = null;

	@ParamDoc(label = "Steps", description = "Diffusion steps. More steps cost proportionally more time", min = "1", order = 190)
	private int steps = 30;

	@ParamDoc(label = "Edit Endpoint", description = "Sidecar path called in EDIT mode", order = 152)
	private String editEndpoint = "/edit";

	@ParamDoc(label = "Mask Endpoint", description = "Sidecar path called in MASK mode", order = 154)
	private String maskEndpoint = "/mask";

	@ParamDoc(label = "Mask Prompt",
		description = "The region to confine an edit to, named in words - \"the boy's hair\". Required in MASK mode; "
			+ "in EDIT mode it is used only when the Mask port is not wired",
		order = 112)
	private String maskPrompt = "";

	@ParamDoc(label = "Negative Prompt",
		description = "What to steer away from. Has no effect unless True CFG Scale is above 1.0", order = 114)
	private String negativePrompt = "";

	@ParamDoc(label = "True CFG Scale",
		description = "Strength of the negative-prompt guidance. At 1.0 the negative branch is not evaluated at all",
		min = "1.0", max = "10.0", step = "0.5", order = 116)
	private double trueCfgScale = 1.0;

	@ParamDoc(label = "Output Resolution",
		description = "Target side length the model renders at, and what an edit's size is derived from. 2048 is native 2K at roughly four times the cost",
		min = "256", max = "2752", order = 175)
	private int outputResolution = 1024;

	@ParamDoc(label = "Composite Through Mask",
		description = "Blend the edit back over the original through the mask, so nothing outside the region can change. "
			+ "Off by default: passing the mask to the model is usually enough, and compositing costs a rescale",
		order = 178)
	private boolean composite = false;

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

	public String getEditEndpoint() {
		return editEndpoint;
	}

	public ImageGenNodeOptions setEditEndpoint(String editEndpoint) {
		this.editEndpoint = editEndpoint;
		return this;
	}

	public String getMaskEndpoint() {
		return maskEndpoint;
	}

	public ImageGenNodeOptions setMaskEndpoint(String maskEndpoint) {
		this.maskEndpoint = maskEndpoint;
		return this;
	}

	public String getMaskPrompt() {
		return maskPrompt;
	}

	public ImageGenNodeOptions setMaskPrompt(String maskPrompt) {
		this.maskPrompt = maskPrompt;
		return this;
	}

	public String getNegativePrompt() {
		return negativePrompt;
	}

	public ImageGenNodeOptions setNegativePrompt(String negativePrompt) {
		this.negativePrompt = negativePrompt;
		return this;
	}

	public double getTrueCfgScale() {
		return trueCfgScale;
	}

	public ImageGenNodeOptions setTrueCfgScale(double trueCfgScale) {
		this.trueCfgScale = trueCfgScale;
		return this;
	}

	public int getOutputResolution() {
		return outputResolution;
	}

	public ImageGenNodeOptions setOutputResolution(int outputResolution) {
		this.outputResolution = outputResolution;
		return this;
	}

	public boolean isComposite() {
		return composite;
	}

	public ImageGenNodeOptions setComposite(boolean composite) {
		this.composite = composite;
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
		// MASK has nothing to segment without one. EDIT does not need it: the mask port
		// may be wired instead, and an edit with no mask at all is the ordinary
		// multi-image compose.
		if (mode == ImageGenMode.MASK && (maskPrompt == null || maskPrompt.isBlank())) {
			errors.add("maskPrompt must not be empty in MASK mode");
		}
		if (trueCfgScale < 1 || trueCfgScale > 10) {
			errors.add("trueCfgScale must be in [1, 10], got " + trueCfgScale);
		}
		if (outputResolution < 256 || outputResolution > 2752) {
			errors.add("outputResolution must be in [256, 2752], got " + outputResolution);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
