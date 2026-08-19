package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

@NodeSpec(nodeId = "tika", name = "Tika Extraction", icon = "description", category = NodeCategory.ANALYSIS,
	description = "Extract metadata and text content using Apache Tika.", defaultConcurrency = 4)
public class TikaNode extends AbstractMediaNode<TikaNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TikaNode.class);

	@PortDoc(label = "Media", description = "The document or container file to parse")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	@PortDoc(label = "Content", description = "The document body Tika extracted, with the markup stripped out")
	public static final OutputPort<String> OUT_CONTENT = OutputPort.one("content", ContentTypeRegistry.TEXT_PLAIN, String.class);

	@PortDoc(label = "Flags", description = "Processing markers recording which parsers Tika ended up using")
	public static final OutputPort<String> OUT_FLAGS = OutputPort.one("flags", ContentTypeRegistry.SCALAR_STRING, String.class);

	/** In-heap skip cache of extracted Tika content, keyed by media path, to avoid re-parsing within this worker's lifetime. Non-durable - the durable
	 * copy lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(50_000);

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
		String path = media.absolutePath();
		if (resultCache.has(path)) {
			String cached = resultCache.get(path);
			ctx.output(OUT_FLAGS, "DONE");
			if (cached != null) {
				ctx.output(OUT_CONTENT, cached);
			}
			return ctx.origin(LOCAL).next();
		}
		try {
			String result = MediaTikaParser.parse(media);
			ctx.output(OUT_FLAGS, "DONE");
			if (result != null) {
				ctx.output(OUT_CONTENT, result);
			}
			resultCache.put(path, result);
			persist(ctx, asset, result);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Error while processing media " + media.path(), e);
			ctx.output(OUT_FLAGS, "FAILED");
			return ctx.failure("failed processing: " + e.getMessage()).abort();
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
