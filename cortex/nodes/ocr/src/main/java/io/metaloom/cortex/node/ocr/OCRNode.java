package io.metaloom.cortex.node.ocr;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.TextPayload;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class OCRNode extends AbstractMediaNode<TextPayload, OCRNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(OCRNode.class);

	public static final String OUTPUT_OCR_TEXT = "ocr_text";

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
	protected NodeResult<TextPayload> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String text = provider.recognizeText(media.file(), options().getLanguage());
		ctx.output(OUTPUT_OCR_TEXT, text);
		ctx.info("OCR extracted " + text.length() + " chars via " + provider.name());
		return ctx.origin(ResultOrigin.COMPUTED).next(TextPayload.of(text));
	}
}
