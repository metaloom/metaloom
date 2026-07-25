package io.metaloom.cortex.node.captioning;

import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.video4j.utils.ImageUtils;
import io.vertx.core.json.JsonObject;

public class CaptioningNode extends AbstractMediaNode<CaptioningNodeOptions> {

	private final SmolVLMClient smolvlmClient;

	public static final NodeOutputKey<String> OUTPUT_CAPTION = NodeOutputKey.of("caption_result", String.class);

	/** Upper bound for the in-heap skip cache. Captioning via the vision model is expensive, so we remember the caption produced for each media during
	 * this worker's lifetime and re-emit it instead of recomputing. Non-durable - the durable copy lives in Loom. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	@Inject
	public CaptioningNode(@Nullable LoomClient client, CortexOptions cortexOption, CaptioningNodeOptions option, SmolVLMClient smolvlmClient) {
		super(client, cortexOption, option);
		this.smolvlmClient = smolvlmClient;
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
				String path = media.absolutePath();
				// Re-emit a locally cached caption instead of re-running the vision model. On a hit the durable copy already exists in Loom, so we
				// also skip re-persisting.
				String cached = resultCache.get(path);
				if (cached != null) {
					metrics.recordAiCacheHit("smolvlm");
					ctx.output(OUTPUT_CAPTION, cached);
					return ctx.origin(LOCAL).next();
				}
				BufferedImage image = ImageUtils.load(media.file());
				long aiStart = System.currentTimeMillis();
				String result;
				try {
					result = smolvlmClient.captionByImage(image, 512);
				} catch (RuntimeException e) {
					metrics.recordAiCall("smolvlm", false, System.currentTimeMillis() - aiStart);
					throw e;
				}
				metrics.recordAiCall("smolvlm", true, System.currentTimeMillis() - aiStart);
				ctx.output(OUTPUT_CAPTION, result);
				resultCache.put(path, result);
				persist(ctx, asset, result);
				return ctx.next();
			} else if (media.isVideo()) {
				return ctx.skipped("not implemented").next();
			} else if (media.isAudio()) {
				return ctx.skipped("not implemented").next();
			} else {
				return NodeResult.failed();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return NodeResult.failed();
		}
	}

	/**
	 * Persist the caption as a {@code caption} JSON component and record a ledger entry. Best-effort and a no-op when the asset is not yet known to Loom
	 * or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String caption) {
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

}
