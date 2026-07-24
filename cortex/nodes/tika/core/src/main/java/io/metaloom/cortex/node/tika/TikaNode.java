package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

public class TikaNode extends AbstractMediaNode<TikaNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TikaNode.class);

	public static final NodeOutputKey<String> OUTPUT_TIKA_FLAGS = NodeOutputKey.of("tika_flags", String.class);
	public static final NodeOutputKey<String> OUTPUT_TIKA_CONTENT = NodeOutputKey.of("tika_content", String.class);

	@Inject
	public TikaNode(@Nullable LoomClient client, CortexOptions cortexOption, TikaNodeOptions options) {
		super(client, cortexOption, options);
	}

	@Override
	public String name() {
		return "tika";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		LoomMedia media = ctx.media();
		return media.isImage() || media.isAudio() || media.isVideo() || media.isDocument();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		try {
			String result = MediaTikaParser.parse(media);
			ctx.output(OUTPUT_TIKA_FLAGS, "DONE");
			if (result != null) {
				ctx.output(OUTPUT_TIKA_CONTENT, result);
			}
			persist(ctx, asset, result);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Error while processing media " + media.path(), e);
			ctx.output(OUTPUT_TIKA_FLAGS, "FAILED");
			return ctx.failure("failed processing").next();
		}
	}

	/**
	 * Persist the extracted Tika content as a {@code tika} JSON component and record a ledger entry. Best-effort and a no-op when the asset is not yet
	 * known to Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String content) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("tika");
			request.setVariant("");
			request.setData(new JsonObject().put("content", content == null ? "" : content));
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, null, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist tika content for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), null, null);
		}
	}

}
