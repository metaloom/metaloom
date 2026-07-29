package io.metaloom.cortex.node.captioning;

import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.metaloom.video4j.utils.ImageUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Captions media with a vision-language model. Images are described from a single still frame via the {@link SmolVLMClient}; videos are described from their
 * temporal content via the OpenAI-compatible {@link VideoVLMClient}, using the strategy selected by {@link CaptioningNodeOptions#getVideoStrategy()}
 * (whole-video multi-image, per-scene timeline, or native {@code video_url}). Both paths persist a JSON component and an {@code asset_node_result} ledger row
 * - images as {@code schemaType=caption}, videos as {@code schemaType=video-caption} (which additionally carries the model, frame count and optional scene
 * breakdown).
 */
public class CaptioningNode extends AbstractMediaNode<CaptioningNodeOptions> {

	private final SmolVLMClient smolvlmClient;
	private final VideoCaptioner videoCaptioner;

	/** The two media alternatives of the descriptor's {@code media_alt} XOR group - one input, two shapes. */
	public static final InputPort<LoomMedia> IN_IMAGE = InputPort.one("image", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);
	public static final InputPort<LoomMedia> IN_VIDEO = InputPort.one("video", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	public static final OutputPort<String> OUT_CAPTION = OutputPort.one("caption", ContentTypeRegistry.TEXT_CAPTION, String.class);

	/** Upper bound for the in-heap skip cache. Captioning via the vision model is expensive, so we remember the caption produced for each media during
	 * this worker's lifetime and re-emit it instead of recomputing. Non-durable - the durable copy lives in Loom. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	@Inject
	public CaptioningNode(@Nullable LoomClient client, CortexOptions cortexOption, CaptioningNodeOptions option, SmolVLMClient smolvlmClient,
		VideoVLMClient videoVlmClient) {
		super(client, cortexOption, option);
		this.smolvlmClient = smolvlmClient;
		this.videoCaptioner = new VideoCaptioner(option, videoVlmClient);
	}

	@Override
	public void initialize() {
		// The video path decodes frames through video4j; images do not need it, but init is idempotent and cheap.
		Video4j.init();
	}

	@Override
	public String name() {
		return "captioning";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isVideo() || ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		try {
			if (media.isImage()) {
				return computeImage(ctx, asset, media);
			} else if (media.isVideo()) {
				return computeVideo(ctx, asset, media);
			} else if (media.isAudio()) {
				return ctx.skipped("not applicable").next();
			} else {
				return NodeResult.failed();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return NodeResult.failed();
		}
	}

	private NodeResult computeImage(NodeContext<LoomMedia> ctx, AssetResponse asset, LoomMedia media) throws Exception {
		String path = media.absolutePath();
		// Re-emit a locally cached caption instead of re-running the vision model. On a hit the durable copy already exists in Loom, so we
		// also skip re-persisting.
		String cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit("smolvlm");
			ctx.output(OUT_CAPTION, cached);
			return ctx.origin(LOCAL).next();
		}
		BufferedImage image = ImageUtils.load(media.file());
		long aiStart = System.currentTimeMillis();
		String result;
		try {
			result = smolvlmClient.captionByImage(image, options().getTargetFrameSize());
		} catch (RuntimeException e) {
			metrics.recordAiCall("smolvlm", false, System.currentTimeMillis() - aiStart);
			throw e;
		}
		metrics.recordAiCall("smolvlm", true, System.currentTimeMillis() - aiStart);
		ctx.output(OUT_CAPTION, result);
		resultCache.put(path, result);
		persistImage(ctx, asset, result);
		return ctx.next();
	}

	private NodeResult computeVideo(NodeContext<LoomMedia> ctx, AssetResponse asset, LoomMedia media) throws Exception {
		String path = media.absolutePath();
		String cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit("video-vlm");
			ctx.output(OUT_CAPTION, cached);
			return ctx.origin(LOCAL).next();
		}
		long aiStart = System.currentTimeMillis();
		VideoCaptionOutput out;
		try {
			out = captionVideo(media);
		} catch (RuntimeException e) {
			metrics.recordAiCall("video-vlm", false, System.currentTimeMillis() - aiStart);
			throw e;
		}
		metrics.recordAiCall("video-vlm", true, System.currentTimeMillis() - aiStart);
		ctx.output(OUT_CAPTION, out.caption());
		resultCache.put(path, out.caption());
		persistVideo(ctx, asset, out);
		return ctx.next();
	}

	/**
	 * Open the media and run the configured video-captioning strategy. Public so the comparison harness can drive the node directly against a live endpoint
	 * without a Loom backend.
	 */
	public VideoCaptionOutput captionVideo(LoomMedia media) throws Exception {
		try (VideoFile video = Videos.open(media.absolutePath())) {
			return videoCaptioner.caption(video);
		}
	}

	/**
	 * Persist an image caption as a {@code caption} JSON component and record a ledger entry. Best-effort and a no-op when the asset is not yet known to Loom
	 * or we run offline.
	 */
	private void persistImage(NodeContext<LoomMedia> ctx, AssetResponse asset, String caption) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("caption");
			request.setVariant("");
			request.setData(new JsonObject().put("caption", caption));
			java.util.UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

	/**
	 * Persist a video caption as a {@code video-caption} JSON component and record a ledger entry. Carries the producing strategy, model, frame count and an
	 * optional per-scene breakdown. Best-effort and a no-op offline / when the asset is unknown.
	 */
	private void persistVideo(NodeContext<LoomMedia> ctx, AssetResponse asset, VideoCaptionOutput out) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonObject data = new JsonObject()
				.put("caption", out.caption())
				.put("variant", options().getVideoStrategy().name().toLowerCase())
				.put("model", options().getVideoModel())
				.put("frameCount", out.frameCount());
			if (out.scenes() != null && !out.scenes().isEmpty()) {
				JsonArray scenes = new JsonArray();
				out.scenes().forEach(s -> scenes.add(new JsonObject()
					.put("seq", s.seq()).put("fromFrame", s.fromFrame()).put("toFrame", s.toFrame()).put("caption", s.caption())));
				data.put("scenes", scenes);
			}
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("video-caption");
			request.setVariant("");
			request.setData(data);
			java.util.UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
