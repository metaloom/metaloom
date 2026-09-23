package io.metaloom.loom.api.options;

/**
 * Options for the MCP {@code generate_image} tool — image generation and editing driven from the
 * chat window.
 *
 * <p>
 * The tool calls an image-generation sidecar <em>directly from Loom</em> rather than through the
 * {@code imagegen} Cortex node, because a node writes its PNG to a path on the worker that produced
 * it and Loom cannot fetch those bytes back. (That is the same gap {@code ProbeEligibility} enforces
 * when it refuses to probe any kind with an {@code artifact/*} output port.) Calling the sidecar
 * here means the result can be ingested as a real asset and shown in the chat.
 * </p>
 *
 * <p>
 * <b>Disabled by default.</b> Without a reachable sidecar the tool can only fail, and a tool that
 * always fails is worse than an absent one — its description is handed verbatim to the model, which
 * will keep choosing it. {@code MCPToolModule} contributes no tool at all when this is off, so it
 * never reaches {@code tools/list}.
 * </p>
 *
 * <p>
 * See {@code spec/loom/MCP.md} and {@code spec/sidecars/QWEN_IMAGE_SIDECAR.md}.
 * </p>
 */
public class ImageGenToolOptions implements Option {

	/**
	 * The qwen-image sidecar's port. Deliberately not 9200 (ideogram) or 9210 (mage-flow): those two
	 * serve {@code /generate} and {@code /remix} only, and this tool's whole point is the
	 * multi-image {@code /edit} endpoint that only the qwen sidecar answers.
	 */
	public static final int DEFAULT_PORT = 9230;

	public static final String DEFAULT_HOST = "localhost";

	/**
	 * The model's own cap is ten condition images. Five is the default here because a chat request
	 * combining more than that is almost always a mistake, and every extra image is another
	 * base64-encoded megabyte read out of storage before the call is even made.
	 */
	public static final int DEFAULT_MAX_IMAGES = 5;

	/**
	 * Generous, because one request can run two diffusion passes: deriving a mask and then applying
	 * the edit. At 2K with 40 steps that is minutes on a busy card.
	 */
	public static final int DEFAULT_TIMEOUT_MS = 300_000;

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_ENABLED", description = "Enable the generate_image MCP tool. Off by default: it needs an image-generation sidecar, and without one the tool can only fail.")
	private boolean enabled = false;

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_HOST", description = "Host of the image-generation sidecar.")
	private String host = DEFAULT_HOST;

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_PORT", description = "Port of the image-generation sidecar. 9230 is the qwen-image sidecar, the only backend serving the multi-image /edit endpoint.")
	private int port = DEFAULT_PORT;

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_MAX_IMAGES", description = "How many input images one generate_image call may combine.")
	private int maxImages = DEFAULT_MAX_IMAGES;

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_TIMEOUT_MS", description = "Wall clock one generate_image sidecar call may take. A masked edit runs two diffusion passes.")
	private int timeoutMs = DEFAULT_TIMEOUT_MS;

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_LIBRARY", description = "UUID of the library generated images are filed into when the request has no input asset to inherit one from. Empty means text-to-image requests are refused with an explanation.")
	private String libraryUuid = "";

	@EnvironmentVariable(name = "LOOM_MCP_IMAGEGEN_STEPS", description = "Diffusion steps per generate_image call.")
	private int steps = 40;

	public boolean isEnabled() {
		return enabled;
	}

	public ImageGenToolOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getHost() {
		return host;
	}

	public ImageGenToolOptions setHost(String host) {
		this.host = host;
		return this;
	}

	public int getPort() {
		return port;
	}

	public ImageGenToolOptions setPort(int port) {
		this.port = port;
		return this;
	}

	public int getMaxImages() {
		return maxImages;
	}

	public ImageGenToolOptions setMaxImages(int maxImages) {
		this.maxImages = maxImages;
		return this;
	}

	public int getTimeoutMs() {
		return timeoutMs;
	}

	public ImageGenToolOptions setTimeoutMs(int timeoutMs) {
		this.timeoutMs = timeoutMs;
		return this;
	}

	public String getLibraryUuid() {
		return libraryUuid;
	}

	public ImageGenToolOptions setLibraryUuid(String libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public int getSteps() {
		return steps;
	}

	public ImageGenToolOptions setSteps(int steps) {
		this.steps = steps;
		return this;
	}
}
