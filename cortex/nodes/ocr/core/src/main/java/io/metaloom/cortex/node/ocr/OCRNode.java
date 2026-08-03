package io.metaloom.cortex.node.ocr;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.vertx.core.json.JsonObject;

import java.util.UUID;

@NodeSpec(nodeId = "ocr", name = "OCR", icon = "text_fields", category = NodeCategory.ANALYSIS,
	description = "Extract text from images using optical character recognition.", defaultConcurrency = 2)
public class OCRNode extends AbstractMediaNode<OCRNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(OCRNode.class);

	@PortDoc(label = "Image", description = "The page or frame to read characters from")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_IMAGE, LoomMedia.class);

	@PortDoc(label = "Text", description = "Everything the OCR engine recognised, in reading order")
	public static final OutputPort<String> OUT_TEXT = OutputPort.one("text", ContentTypeRegistry.TEXT_PLAIN, String.class);

	/** In-heap skip cache of recognized text, keyed by media path, to avoid re-running OCR within this worker's lifetime. Non-durable - the durable copy
	 * lives in Loom. */
	private final LocalResultCache<String> resultCache = new LocalResultCache<>(50_000);

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
		String path = media.absolutePath();
		String cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit("tesseract");
			ctx.output(OUT_TEXT, cached);
			return ctx.origin(ResultOrigin.LOCAL).next();
		}
		long aiStart = System.currentTimeMillis();
		String text;
		try {
			text = provider.recognizeText(media.file(), options().getLanguage());
		} catch (Exception e) {
			metrics.recordAiCall("tesseract", false, System.currentTimeMillis() - aiStart);
			throw e;
		}
		metrics.recordAiCall("tesseract", true, System.currentTimeMillis() - aiStart);
		ctx.output(OUT_TEXT, text);
		ctx.info("OCR extracted " + text.length() + " chars via " + provider.name());
		resultCache.put(path, text);
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
