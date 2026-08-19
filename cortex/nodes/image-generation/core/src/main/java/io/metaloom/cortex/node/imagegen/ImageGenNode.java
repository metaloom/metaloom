package io.metaloom.cortex.node.imagegen;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

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
public class ImageGenNode extends AbstractMediaNode<ImageGenNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(ImageGenNode.class);

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

	/** In-heap skip cache of the generated image path, keyed by media path, to avoid re-generating within this worker's lifetime. The rendered PNG itself
	 * is a durable local artifact under {@code metaPath/imagegen_bin}. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final ImageGenClient imageGenClient;
	private final CortexOptions cortexOptions;

	@Inject
	public ImageGenNode(@Nullable LoomClient client, CortexOptions cortexOptions, ImageGenNodeOptions options, ImageGenClient imageGenClient) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.imageGenClient = imageGenClient;
	}

	@Override
	public String name() {
		return "imagegen";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// Re-emit a locally cached image path instead of re-generating. On a hit the ledger entry already exists in Loom, so we also skip re-persisting.
		String cached = resultCache.get(path);
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
				png = generate(ctx, media);
			} catch (RuntimeException e) {
				metrics.recordAiCall("imagegen", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("imagegen", true, System.currentTimeMillis() - aiStart);

			Path imagePath = resolveImagePath(media);
			Files.createDirectories(imagePath.getParent());
			Files.write(imagePath, png);

			ctx.print("DONE", png.length + " bytes");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_IMAGE, imagePath.toString());
			resultCache.put(path, imagePath.toString());

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
	 * Call the sidecar for the configured mode: REMIX loads the source image and hits {@code /remix}; GENERATE (default) hits {@code /generate}.
	 */
	private byte[] generate(NodeContext<LoomMedia> ctx, LoomMedia media) throws IOException {
		ImageGenNodeOptions o = options();
		// A wired prompt port wins over the configured one: the option is the default for a
		// standalone node, the edge is what a pipeline author explicitly connected.
		String prompt = ctx.optionalInput(IN_PROMPT).orElseGet(o::getPrompt);
		if (o.getMode() == ImageGenMode.REMIX) {
			BufferedImage source = ImageIO.read(media.file());
			if (source == null) {
				throw new IOException("Could not read source image: " + media.absolutePath());
			}
			return imageGenClient.remix(source, prompt, o.getStrength(), o.getSeed(), o.getSteps());
		}
		return imageGenClient.generate(prompt, o.getWidth(), o.getHeight(), o.getSeed(), o.getSteps());
	}

	/**
	 * Resolve the local cache path for the generated PNG: {@code metaPath/imagegen_bin/<segment>/<sha512>.png}. Mirrors {@code ThumbnailNode}/{@code TtsNode}.
	 */
	private Path resolveImagePath(LoomMedia media) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + ".png";
		Path basePath = cortexOptions.getMetaPath().resolve("imagegen_bin");
		Path dirPath = HashUtils.segmentPath(basePath, hash);
		return dirPath.resolve(fileName);
	}
}
