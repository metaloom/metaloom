package io.metaloom.cortex.node.imagegen;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Image-generation node. Unlike the analysis nodes it does not annotate the media
 * with a property of the media itself - it <em>generates</em> a new image from a
 * configured prompt (mode {@link ImageGenMode#GENERATE}) or remixes the asset's own
 * image (mode {@link ImageGenMode#REMIX}) and attaches it to the asset.
 *
 * <p>
 * The diffusion inference runs in the FastAPI image sidecar (see
 * {@code sidecars/ideogram-sidecar}); this node is a pure HTTP client via
 * {@link ImageGenClient}.
 * </p>
 *
 * <p>
 * Following the {@code ThumbnailNode}/{@code TtsNode} pattern, the generated PNG is
 * written to a local cache under {@code metaPath/imagegen_bin} and only the
 * {@code asset_node_result} ledger entry is recorded in Loom - the bytes stay local
 * (there is no byte-ingest endpoint for produced media yet).
 * </p>
 */
@NodeSpec(nodeId = "imagegen", name = "Image Generation", icon = "auto_awesome", category = NodeCategory.TRANSFORM,
	description = "Generate an image through the image-generation sidecar - text-to-image from a prompt, or "
		+ "image-to-image from the source asset. The PNG is written to the worker's local cache; wire it into a "
		+ "sink to keep it.",
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor advertises
	// it. This node does, and puts it last in the form.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)", description = "Wall-clock budget per item",
		min = "1", order = 210))
public class ImageGenNode extends AbstractMediaNode<ImageGenNodeOptions> implements PipelineConfigurable {

	public static final Logger log = LoggerFactory.getLogger(ImageGenNode.class);

	public static final String KIND = "imagegen";

	@PortDoc(label = "Prompt", required = false,
		description = "Upstream text used instead of the configured prompt - an LLM answer or a caption")
	public static final InputPort<String> IN_PROMPT = InputPort.one("prompt", ContentTypeRegistry.TEXT_ANY, String.class);

	@PortDoc(label = "Source Image", required = false,
		description = "The image to remix. Required in REMIX mode and ignored in GENERATE mode")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	@PortDoc(label = "Image", description = "The generated PNG in the worker's local cache; wire it into a sink to keep it")
	public static final OutputPort<String> OUT_IMAGE = OutputPort.one("image", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** In-heap skip cache of the generated image path, keyed by media path plus the option digest, to avoid re-generating within this worker's
	 * lifetime. The digest is in the key for the same reason it is in the file name — see {@link #digest(String)}. The rendered PNG itself
	 * is a durable local artifact under {@code metaPath/imagegen_bin}. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final ImageGenClient imageGenClient;
	private final CortexOptions cortexOptions;

	/** Graph-local id, which is the ledger {@code node_id} — what lets two differently configured instances coexist on one asset. */
	private String nodeId = KIND;

	// Per-instance overrides of the result-affecting options, null when the instance did not configure
	// them. Held on the node rather than written into options() because that object may be the
	// worker-shared YAML instance (AbstractNodeModule.nodeOptions) — mutating it would reconfigure
	// every other instance of this node on the worker. Same reasoning as FacedetectNode.
	private ImageGenMode mode;
	private String prompt;
	private Integer width;
	private Integer height;
	private Double strength;
	private Integer seed;
	private Integer steps;

	@Inject
	public ImageGenNode(@Nullable LoomClient client, CortexOptions cortexOptions, ImageGenNodeOptions options, ImageGenClient imageGenClient) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.imageGenClient = imageGenClient;
	}

	@Override
	public String name() {
		return KIND;
	}

	/** The graph-local instance id — the ledger {@code node_id}. See the field for why the override matters. */
	@Override
	protected String nodeId() {
		return nodeId;
	}

	/**
	 * Apply the per-instance configuration from the pipeline node definition.
	 *
	 * <p>
	 * The prompt <em>is</em> the work here — two {@code imagegen} nodes in one graph render two different prompts — so the result-affecting options
	 * are per instance, exactly like {@code script}. Environmental options (host, port, endpoints, timeout) stay worker-scoped in {@code cortex.yml}.
	 * </p>
	 */
	@Override
	public void configure(JsonObject nodeDef) {
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("mode")) {
			String raw = nodeDef.getString("mode");
			try {
				mode = ImageGenMode.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("Image generation node '" + nodeId + "': unknown mode '" + raw + "'");
			}
		}
		if (nodeDef.containsKey("prompt")) {
			prompt = nodeDef.getString("prompt");
		}
		if (nodeDef.containsKey("width")) {
			width = positiveInt(nodeDef, "width");
		}
		if (nodeDef.containsKey("height")) {
			height = positiveInt(nodeDef, "height");
		}
		if (nodeDef.containsKey("steps")) {
			steps = positiveInt(nodeDef, "steps");
		}
		if (nodeDef.containsKey("seed")) {
			seed = nodeDef.getInteger("seed");
		}
		if (nodeDef.containsKey("strength")) {
			double value = nodeDef.getDouble("strength");
			// Mirrors ImageGenNodeOptions.validate(), which a per-instance value never passes through.
			if (value <= 0 || value > 1) {
				throw new IllegalStateException("Image generation node '" + nodeId + "': strength must be in (0, 1], got " + value);
			}
			strength = value;
		}
	}

	private int positiveInt(JsonObject nodeDef, String key) {
		Integer value = nodeDef.getInteger(key);
		if (value == null || value <= 0) {
			throw new IllegalStateException("Image generation node '" + nodeId + "': " + key + " must be a positive number, got '"
				+ nodeDef.getValue(key) + "'");
		}
		return value;
	}

	// The effective value of each result-affecting option: this instance's override, or the worker's.

	private ImageGenMode mode() {
		return mode != null ? mode : options().getMode();
	}

	private String configuredPrompt() {
		return prompt != null ? prompt : options().getPrompt();
	}

	private int width() {
		return width != null ? width : options().getWidth();
	}

	private int height() {
		return height != null ? height : options().getHeight();
	}

	private double strength() {
		return strength != null ? strength : options().getStrength();
	}

	private Integer seed() {
		return seed != null ? seed : options().getSeed();
	}

	private int steps() {
		return steps != null ? steps : options().getSteps();
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// A wired prompt port wins over the configured one: the option is the default for a
		// standalone node, the edge is what a pipeline author explicitly connected.
		String prompt = ctx.optionalInput(IN_PROMPT).orElseGet(this::configuredPrompt);
		String digest = digest(prompt);
		String cacheKey = path + "|" + digest;

		// Re-emit a locally cached image path instead of re-generating. On a hit the ledger entry already exists in Loom, so we also skip re-persisting.
		String cached = resultCache.get(cacheKey);
		if (cached != null) {
			metrics.recordAiCacheHit("imagegen");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_IMAGE, cached);
			return ctx.origin(LOCAL).next();
		}

		try {
			long aiStart = System.currentTimeMillis();
			byte[] png;
			try {
				png = generate(prompt, media);
			} catch (RuntimeException e) {
				metrics.recordAiCall("imagegen", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("imagegen", true, System.currentTimeMillis() - aiStart);

			Path imagePath = resolveImagePath(media, digest);
			Files.createDirectories(imagePath.getParent());
			Files.write(imagePath, png);

			ctx.print("DONE", png.length + " bytes");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_IMAGE, imagePath.toString());
			resultCache.put(cacheKey, imagePath.toString());

			// The image bytes live in the local imagegen_bin cache; record the ledger marker that this node produced them for the asset. Uploading the bytes
			// into the asset binary subsystem needs a byte-ingest endpoint that does not exist yet, so that remains a follow-up (same as ThumbnailNode/TtsNode).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to generate image for media {}", path, e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * Call the sidecar for the effective mode: REMIX loads the source image and hits {@code /remix}; GENERATE (default) hits {@code /generate}.
	 */
	private byte[] generate(String prompt, LoomMedia media) throws IOException {
		if (mode() == ImageGenMode.REMIX) {
			BufferedImage source = ImageIO.read(media.file());
			if (source == null) {
				throw new IOException("Could not read source image: " + media.absolutePath());
			}
			return imageGenClient.remix(source, prompt, strength(), seed(), steps());
		}
		return imageGenClient.generate(prompt, width(), height(), seed(), steps());
	}

	/**
	 * Resolve the local cache path for the generated PNG:
	 * {@code metaPath/imagegen_bin/<segment>/<sha512>-<digest>.png}. Mirrors {@code Sam2Node}: the
	 * digest is in the file name, not only in the cache key, so two instances of this node in one
	 * graph — the obvious way to render two prompts — cannot write to the same path and serve each
	 * other's result.
	 */
	private Path resolveImagePath(LoomMedia media, String digest) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + "-" + digest + ".png";
		Path basePath = cortexOptions.getMetaPath().resolve("imagegen_bin");
		Path dirPath = HashUtils.segmentPath(basePath, hash);
		return dirPath.resolve(fileName);
	}

	/**
	 * A short digest of everything that changes the produced image — the effective prompt (wired or
	 * configured) and the result-affecting options. Copied from the {@code Sam2Node} /
	 * {@code ImageManipulationNode} pattern.
	 */
	private String digest(String prompt) {
		String material = mode() + "|" + prompt + "|" + width() + "x" + height() + "|" + strength() + "|" + seed() + "|" + steps();
		return sha256Hex(material).substring(0, 12);
	}

	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
		}
	}
}
