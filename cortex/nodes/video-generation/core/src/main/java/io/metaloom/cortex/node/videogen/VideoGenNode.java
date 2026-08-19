package io.metaloom.cortex.node.videogen;

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
 * Video-generation node. Like the {@code ImageGenNode} it does not annotate the media
 * with a property of the media itself - it <em>generates</em> a new video clip from a
 * configured prompt (mode {@link VideoGenMode#GENERATE}, text-to-video) or animates the
 * asset's own image (mode {@link VideoGenMode#ANIMATE}, image-to-video) and attaches it
 * to the asset.
 *
 * <p>
 * The diffusion inference runs in the FastAPI LTX-2 sidecar (see
 * {@code sidecars/ltx2-sidecar}); this node is a pure HTTP client via
 * {@link VideoGenClient}. The produced clip is an MP4 with LTX-2's synchronised audio
 * track.
 * </p>
 *
 * <p>
 * Following the {@code ImageGenNode}/{@code TtsNode} pattern, the generated MP4 is
 * written to a local cache under {@code metaPath/videogen_bin} and only the
 * {@code asset_node_result} ledger entry is recorded in Loom - the bytes stay local
 * (there is no byte-ingest endpoint for produced media yet; wire a sink to keep it).
 * </p>
 */
@NodeSpec(nodeId = "videogen", name = "Video Generation", icon = "movie", category = NodeCategory.TRANSFORM,
	description = "Generate a short video clip through the LTX-2 video sidecar - text-to-video from a prompt, or "
		+ "image-to-video from the source asset. The MP4 (with synchronised audio) is written to the worker's local "
		+ "cache; wire it into a sink to keep it.",
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor advertises
	// it. This node does, and puts it last in the form.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)", description = "Wall-clock budget per item",
		min = "1", order = 240))
public class VideoGenNode extends AbstractMediaNode<VideoGenNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(VideoGenNode.class);

	@PortDoc(label = "Prompt", required = false,
		description = "Upstream text used instead of the configured prompt - an LLM answer or a caption")
	public static final InputPort<String> IN_PROMPT = InputPort.one("prompt", ContentTypeRegistry.TEXT_ANY, String.class);

	@PortDoc(label = "Source Image", required = false,
		description = "The still image to animate. Required in ANIMATE mode and ignored in GENERATE mode")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	@PortDoc(label = "Video", description = "The generated MP4 in the worker's local cache; wire it into a sink to keep it")
	public static final OutputPort<String> OUT_VIDEO = OutputPort.one("video", ContentTypeRegistry.ARTIFACT_VIDEO, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** In-heap skip cache of the generated video path, keyed by media path, to avoid re-generating within this worker's lifetime. The rendered MP4 itself
	 * is a durable local artifact under {@code metaPath/videogen_bin}. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final VideoGenClient videoGenClient;
	private final CortexOptions cortexOptions;

	@Inject
	public VideoGenNode(@Nullable LoomClient client, CortexOptions cortexOptions, VideoGenNodeOptions options, VideoGenClient videoGenClient) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.videoGenClient = videoGenClient;
	}

	@Override
	public String name() {
		return "videogen";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		// Both modes attach to an image asset: ANIMATE uses its pixels as the opening frame,
		// GENERATE ignores them and works from the prompt (as ImageGenNode's GENERATE does).
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// Re-emit a locally cached video path instead of re-generating. On a hit the ledger entry already exists in Loom, so we also skip re-persisting.
		String cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit("videogen");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_VIDEO, cached);
			return ctx.origin(LOCAL).next();
		}

		try {
			long aiStart = System.currentTimeMillis();
			byte[] mp4;
			try {
				mp4 = generate(ctx, media);
			} catch (RuntimeException e) {
				metrics.recordAiCall("videogen", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("videogen", true, System.currentTimeMillis() - aiStart);

			Path videoPath = resolveVideoPath(media);
			Files.createDirectories(videoPath.getParent());
			Files.write(videoPath, mp4);

			ctx.print("DONE", mp4.length + " bytes");
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_VIDEO, videoPath.toString());
			resultCache.put(path, videoPath.toString());

			// The video bytes live in the local videogen_bin cache; record the ledger marker that this node produced them for the asset. Uploading the bytes
			// into the asset binary subsystem needs a byte-ingest endpoint that does not exist yet, so that remains a follow-up (same as ImageGenNode/TtsNode).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to generate video for media {}", path, e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * Call the sidecar for the configured mode: ANIMATE loads the source image and hits {@code /animate}; GENERATE (default) hits {@code /generate}.
	 */
	private byte[] generate(NodeContext<LoomMedia> ctx, LoomMedia media) throws IOException {
		VideoGenNodeOptions o = options();
		// A wired prompt port wins over the configured one: the option is the default for a
		// standalone node, the edge is what a pipeline author explicitly connected.
		String prompt = ctx.optionalInput(IN_PROMPT).orElseGet(o::getPrompt);
		if (o.getMode() == VideoGenMode.ANIMATE) {
			BufferedImage source = ImageIO.read(media.file());
			if (source == null) {
				throw new IOException("Could not read source image: " + media.absolutePath());
			}
			return videoGenClient.animate(source, prompt, o.getNegativePrompt(), o.getWidth(), o.getHeight(), o.getNumFrames(), o.getFps(),
				o.getSteps(), o.getGuidance(), o.getSeed());
		}
		return videoGenClient.generate(prompt, o.getNegativePrompt(), o.getWidth(), o.getHeight(), o.getNumFrames(), o.getFps(), o.getSteps(),
			o.getGuidance(), o.getSeed());
	}

	/**
	 * Resolve the local cache path for the generated MP4: {@code metaPath/videogen_bin/<segment>/<sha512>.mp4}. Mirrors {@code ImageGenNode}/{@code TtsNode}.
	 */
	private Path resolveVideoPath(LoomMedia media) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + ".mp4";
		Path basePath = cortexOptions.getMetaPath().resolve("videogen_bin");
		Path dirPath = HashUtils.segmentPath(basePath, hash);
		return dirPath.resolve(fileName);
	}
}
