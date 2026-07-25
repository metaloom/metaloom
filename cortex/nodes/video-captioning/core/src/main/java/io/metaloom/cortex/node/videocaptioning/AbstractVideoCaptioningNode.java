package io.metaloom.cortex.node.videocaptioning;

import java.io.IOException;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;
import io.vertx.core.json.JsonObject;

/**
 * Shared skeleton for the video-captioning variants. Each variant only implements {@link #doCaption(VideoFile)} - the strategy for turning a video into a
 * caption (whole-video multi-image, scene-first, or native video). Frame decoding, output wiring and Loom persistence are handled here, mirroring the
 * existing image {@code CaptioningNode}.
 */
public abstract class AbstractVideoCaptioningNode extends AbstractMediaNode<VideoCaptioningNodeOptions> {

	private static final Logger log = LoggerFactory.getLogger(AbstractVideoCaptioningNode.class);

	public static final NodeOutputKey<String> OUTPUT_CAPTION = NodeOutputKey.of("video_caption_result", String.class);

	protected final VideoVLMClient vlm;

	protected AbstractVideoCaptioningNode(@Nullable LoomClient client, CortexOptions cortexOptions, VideoCaptioningNodeOptions options, VideoVLMClient vlm) {
		super(client, cortexOptions, options);
		this.vlm = vlm;
	}

	@Override
	public void initialize() {
		Video4j.init();
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return options().isEnabled() && ctx.media().isVideo();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		try {
			VideoCaptionOutput out = captionMedia(media);
			ctx.output(OUTPUT_CAPTION, out.caption());
			persist(ctx, asset, out);
			return ctx.next();
		} catch (Exception e) {
			log.error("Video captioning failed for {}", media.absolutePath(), e);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Open the media and run the variant's captioning strategy. Exposed (public) so the comparison harness can drive the node directly against a live
	 * endpoint without a Loom backend.
	 */
	public VideoCaptionOutput captionMedia(LoomMedia media) throws Exception {
		try (VideoFile video = Videos.open(media.absolutePath())) {
			return doCaption(video);
		}
	}

	/** Variant-specific strategy: turn an opened video into a caption (+ optional per-scene detail). */
	protected abstract VideoCaptionOutput doCaption(VideoFile video) throws Exception;

	/**
	 * Persist the caption as a {@code video-caption} JSON component and record the ledger entry. Best-effort and a no-op offline / when the asset is unknown
	 * - the comparison harness runs with a null client and simply skips this.
	 */
	protected void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, VideoCaptionOutput out) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonObject data = new JsonObject()
				.put("caption", out.caption())
				.put("variant", name())
				.put("model", vlm.model())
				.put("frameCount", out.frameCount());
			if (out.scenes() != null && !out.scenes().isEmpty()) {
				io.vertx.core.json.JsonArray scenes = new io.vertx.core.json.JsonArray();
				out.scenes().forEach(s -> scenes.add(new JsonObject()
					.put("seq", s.seq()).put("fromFrame", s.fromFrame()).put("toFrame", s.toFrame()).put("caption", s.caption())));
				data.put("scenes", scenes);
			}
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("video-caption");
			request.setVariant("");
			request.setData(data);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}
}
