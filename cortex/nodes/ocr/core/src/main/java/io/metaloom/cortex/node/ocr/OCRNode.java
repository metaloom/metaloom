package io.metaloom.cortex.node.ocr;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

public class OCRNode extends AbstractMediaNode<OCRNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(OCRNode.class);

	public static final NodeOutputKey<String> OUTPUT_OCR_TEXT = NodeOutputKey.of("ocr_text", String.class);

	private final OCRProvider provider;

	@Inject
	public OCRNode(@Nullable LoomClient client, CortexOptions options, OCRNodeOptions nodeOptions, OCRProvider provider) {
		super(client, options, nodeOptions);
		this.provider = provider;
	}

	@Override
	public String name() {
		return "ocr";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return ctx.media().isImage();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String text = provider.recognizeText(media.file(), options().getLanguage());
		ctx.output(OUTPUT_OCR_TEXT, text);
		ctx.info("OCR extracted " + text.length() + " chars via " + provider.name());
		persist(ctx, asset, text);
		return ctx.origin(ResultOrigin.COMPUTED).next();
	}

	/**
	 * Persist the recognized text as an {@code ocr} JSON component and record a ledger entry. Best-effort and a no-op when the asset is not yet known to
	 * Loom or we run offline.
	 */
	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, String text) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType("ocr");
			request.setVariant("");
			request.setData(new JsonObject().put("text", text));
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, provider.name(), resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist ocr text for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), provider.name(), null);
		}
	}
}
