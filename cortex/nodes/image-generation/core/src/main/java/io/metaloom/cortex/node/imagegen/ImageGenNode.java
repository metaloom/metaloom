package io.metaloom.cortex.node.imagegen;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.Element;
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
 * with a property of the media itself - it <em>generates</em> a new image, in one of
 * four modes: from a prompt alone ({@link ImageGenMode#GENERATE}), from the asset's own
 * image ({@link ImageGenMode#REMIX}), from the asset's image plus any number of wired
 * reference images and an optional region mask ({@link ImageGenMode#EDIT}), or as a
 * binary mask of a named region ({@link ImageGenMode#MASK}).
 *
 * <p>
 * The diffusion inference runs in a FastAPI image sidecar; this node is a pure HTTP
 * client via {@link ImageGenClient}. Three backends serve the same contract and are
 * selected by the {@code port} option alone - there is no backend enum. Only
 * {@code qwen-image-sidecar} (9230) serves {@code /edit} and {@code /mask}, so
 * {@code EDIT} and {@code MASK} against ideogram (9200) or mage-flow (9210) fail the
 * item with that sidecar's 404.
 * </p>

 * <p>
 * <strong>The mask is an image, not a parameter.</strong> {@code MASK} produces one on
 * the ordinary {@code image} output port, and {@code EDIT} consumes one on the
 * {@code mask} input port, so "change only the hair" is two instances of this node wired
 * together rather than a special code path. That also makes the intermediate mask a real
 * artifact you can look at, which matters: a wrong mask yields a plausible edit in
 * entirely the wrong place.
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
	description = "Generate an image through the image-generation sidecar - text-to-image from a prompt, "
		+ "image-to-image from the source asset, a multi-image edit combining wired reference images, or a "
		+ "binary mask of a named region. The PNG is written to the worker's local cache; wire it into a "
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

	// artifact/image carrying a path String, NOT media/image carrying LoomMedia: ValueCoercer
	// coerces every media, text, hash and artifact value to a String, so an InputPort<LoomMedia>
	// typed media/image throws ValueCoercionException on ctx.input(). S3SinkNode.IN_ARTIFACTS is
	// the one working precedent in the tree for consuming an upstream artifact path, and these two
	// follow it. It also means these ports take *produced* images - another node's output - which
	// is exactly what they are for; the asset's own picture arrives via ctx.media().

	@PortDoc(label = "Reference Images", required = false,
		description = "Further images combined into the result alongside the asset's own picture. Up to nine, in EDIT mode")
	public static final InputPort<String> IN_REFERENCES = InputPort.many("references", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Region Mask", required = false,
		description = "A binary mask, white where the edit applies. Wire a MASK-mode instance of this node here")
	public static final InputPort<String> IN_MASK = InputPort.one("mask", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Image", description = "The generated PNG in the worker's local cache; wire it into a sink to keep it")
	public static final OutputPort<String> OUT_IMAGE = OutputPort.one("image", ContentTypeRegistry.ARTIFACT_IMAGE, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** In-heap skip cache of the generated image path, keyed by media path plus the option digest, to avoid re-generating within this worker's
	 * lifetime. The digest is in the key for the same reason it is in the file name — see {@link #digest(String)}. The rendered PNG itself
	 * is a durable local artifact under {@code metaPath/imagegen_bin}. */
	/** The condition list the sidecar accepts is capped at ten, which is the model's own limit. One
	 * slot is the asset's own image and one may be the mask, so nine references is the most that can
	 * ever be useful - the node trims rather than letting the sidecar 400 a whole item. */
	private static final int MAX_REFERENCES = 9;

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
	private String maskPrompt;
	private String negativePrompt;
	private Double trueCfgScale;
	private Integer outputResolution;
	private Boolean composite;

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
		if (nodeDef.containsKey("maskPrompt")) {
			maskPrompt = nodeDef.getString("maskPrompt");
		}
		if (nodeDef.containsKey("negativePrompt")) {
			negativePrompt = nodeDef.getString("negativePrompt");
		}
		if (nodeDef.containsKey("composite")) {
			composite = nodeDef.getBoolean("composite");
		}
		if (nodeDef.containsKey("outputResolution")) {
			outputResolution = positiveInt(nodeDef, "outputResolution");
		}
		if (nodeDef.containsKey("trueCfgScale")) {
			double value = nodeDef.getDouble("trueCfgScale");
			// Mirrors ImageGenNodeOptions.validate(), which a per-instance value never passes through.
			if (value < 1 || value > 10) {
				throw new IllegalStateException("Image generation node '" + nodeId + "': trueCfgScale must be in [1, 10], got " + value);
			}
			trueCfgScale = value;
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

	private String maskPrompt() {
		return maskPrompt != null ? maskPrompt : options().getMaskPrompt();
	}

	private String negativePrompt() {
		return negativePrompt != null ? negativePrompt : options().getNegativePrompt();
	}

	private double trueCfgScale() {
		return trueCfgScale != null ? trueCfgScale : options().getTrueCfgScale();
	}

	private int outputResolution() {
		return outputResolution != null ? outputResolution : options().getOutputResolution();
	}

	private boolean composite() {
		return composite != null ? composite : options().isComposite();
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

		// The wired images change the result as surely as any option does, so they are digest
		// material. Without them two runs over the same asset with different references would
		// collide on one file name and serve each other's picture.
		List<String> referencePaths = referencePaths(ctx);
		String maskPath = ctx.optionalInput(IN_MASK).orElse(null);

		String digest = digest(prompt, referencePaths, maskPath);
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
			ImageGenResult result;
			try {
				result = generate(ctx, prompt, media, referencePaths, maskPath);
			} catch (RuntimeException e) {
				metrics.recordAiCall("imagegen", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("imagegen", true, System.currentTimeMillis() - aiStart);

			byte[] png = result.png();
			Path imagePath = resolveImagePath(media, digest);
			Files.createDirectories(imagePath.getParent());
			Files.write(imagePath, png);

			ctx.print("DONE", png.length + " bytes");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_IMAGE, imagePath.toString());
			resultCache.put(cacheKey, imagePath.toString());

			// The image bytes live in the local imagegen_bin cache; record the ledger marker that this node produced them for the asset. Uploading the bytes
			// into the asset binary subsystem needs a byte-ingest endpoint that does not exist yet, so that remains a follow-up (same as ThumbnailNode/TtsNode).
			// producerVersion is the sidecar's own X-Model-Id, so a ledger row can say WHICH model
			// drew the picture - worth having now that three backends answer one contract behind a
			// port number and do not share a weight licence. Null against the two older backends,
			// which do not send the header; the row then looks exactly as it always did.
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, result.modelId(), null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to generate image for media {}", path, e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * The paths wired into {@code references}, in port order, capped at {@link #MAX_REFERENCES}.
	 *
	 * <p>
	 * A MANY port so several upstream images arrive in <em>one</em> invocation. Were it ONE, a
	 * MANY output feeding it would run this node once per element - which is several unrelated
	 * pictures rather than one picture combining them, the opposite of the point.
	 * </p>
	 */
	private List<String> referencePaths(NodeContext<LoomMedia> ctx) {
		if (!ctx.isWired(IN_REFERENCES)) {
			return List.of();
		}
		List<String> paths = new ArrayList<>();
		for (Element<String> element : ctx.inputs(IN_REFERENCES)) {
			if (element.value() != null && !element.value().isBlank()) {
				paths.add(element.value());
			}
		}
		if (paths.size() > MAX_REFERENCES) {
			log.warn("Node '{}' was wired {} reference images but the model takes at most {} - dropping the rest",
				nodeId, paths.size(), MAX_REFERENCES);
			paths = paths.subList(0, MAX_REFERENCES);
		}
		return paths;
	}

	/**
	 * Call the sidecar for the effective mode.
	 *
	 * <ul>
	 * <li>{@code GENERATE} hits {@code /generate} and never looks at the source pixels.</li>
	 * <li>{@code REMIX} loads the asset's image and hits {@code /remix}.</li>
	 * <li>{@code EDIT} hits {@code /edit} with the asset's image first and the wired references
	 * after - the order is significant, because the sidecar edits element 0 and treats the rest as
	 * references.</li>
	 * <li>{@code MASK} hits {@code /mask}.</li>
	 * </ul>
	 */
	private ImageGenResult generate(NodeContext<LoomMedia> ctx, String prompt, LoomMedia media, List<String> referencePaths, String maskPath)
		throws IOException {
		switch (mode()) {
		case REMIX:
			return imageGenClient.remix(readSource(media), prompt, strength(), seed(), steps(), negativePrompt(), trueCfgScale());

		case MASK:
			// In MASK mode the node's entire output is a mask, so what the prompt names is a
			// REGION, not a picture. A wired prompt port therefore still wins - an upstream LLM
			// answering "which part of this do you mean" is exactly the useful thing to connect -
			// and maskPrompt is the fallback for a node standing on its own, which validate()
			// guarantees is non-blank in this mode.
			String subject = ctx.optionalInput(IN_PROMPT).orElseGet(this::maskPrompt);
			return imageGenClient.mask(readSource(media), subject, seed(), steps(), outputResolution());

		case EDIT:
			List<BufferedImage> images = new ArrayList<>();
			// Element 0 is the image being edited. Everything after it is a reference.
			images.add(readSource(media));
			for (String reference : referencePaths) {
				images.add(readArtifact(reference, "reference image"));
			}
			// A wired mask beats the configured maskPrompt, on the same reasoning as the prompt
			// port: the option is the default for a node standing alone, the edge is what a
			// pipeline author explicitly connected. Only one of the two is ever sent - the sidecar
			// rejects both together, deliberately.
			byte[] maskPng = maskPath != null ? readArtifactBytes(maskPath, "region mask") : null;
			String maskPrompt = maskPng != null ? null : blankToNull(maskPrompt());
			if (composite() && maskPng == null && maskPrompt == null) {
				throw new IOException("Node '" + nodeId + "' has composite enabled but no mask: wire the mask port or set maskPrompt");
			}
			return imageGenClient.edit(images, maskPng, maskPrompt, prompt, seed(), steps(), negativePrompt(), trueCfgScale(),
				outputResolution(), composite());

		case GENERATE:
		default:
			return imageGenClient.generate(prompt, width(), height(), seed(), steps(), negativePrompt(), trueCfgScale());
		}
	}

	private BufferedImage readSource(LoomMedia media) throws IOException {
		BufferedImage source = ImageIO.read(media.file());
		if (source == null) {
			throw new IOException("Could not read source image: " + media.absolutePath());
		}
		return source;
	}

	/**
	 * Read an image another node produced, as pixels.
	 */
	private BufferedImage readArtifact(String path, String what) throws IOException {
		BufferedImage image = ImageIO.read(requireArtifact(path, what));
		if (image == null) {
			throw new IOException("Node '" + nodeId + "': the " + what + " '" + path + "' is not a readable image");
		}
		return image;
	}

	/**
	 * Read an image another node produced, as bytes - the mask is forwarded verbatim rather than
	 * decoded, because the sidecar has to binarize it and a round trip through {@code BufferedImage}
	 * would only risk changing it on the way.
	 */
	private byte[] readArtifactBytes(String path, String what) throws IOException {
		return Files.readAllBytes(requireArtifact(path, what).toPath());
	}

	/**
	 * An artifact path is <em>worker-local</em>, so a missing file almost always means the producing
	 * node ran on a different worker. Say that, because "could not read image" sends people looking
	 * at the file system instead of at their affinity groups.
	 */
	private File requireArtifact(String path, String what) throws IOException {
		File file = new File(path);
		if (!file.isFile()) {
			throw new IOException("Node '" + nodeId + "': the " + what + " '" + path + "' does not exist on this worker. "
				+ "Artifact paths are worker-local - pin the producing node and this one into one affinity group.");
		}
		return file;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
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
	private String digest(String prompt, List<String> referencePaths, String maskPath) {
		// EVERY result-affecting input belongs here. A new option left out of this string is not a
		// cosmetic omission: the path and the cache key both derive from it, so two instances
		// differing only in the forgotten option write to one file and serve each other's picture.
		String material = mode() + "|" + prompt + "|" + width() + "x" + height() + "|" + strength() + "|" + seed() + "|" + steps()
			+ "|" + maskPrompt() + "|" + negativePrompt() + "|" + trueCfgScale() + "|" + outputResolution() + "|" + composite()
			+ "|" + String.join(",", referencePaths) + "|" + maskPath;
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
