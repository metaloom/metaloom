package io.metaloom.cortex.node.captioning;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.utils.ImageUtils;

public class CaptioningNode extends AbstractMediaNode<CaptioningNodeOptions> {

	private final SmolVLMClient smolvlmClient;

	public static final NodeOutputKey<String> OUTPUT_CAPTION = NodeOutputKey.of("caption_result", String.class);

	@Inject
	public CaptioningNode(@Nullable LoomClient client, CortexOptions cortexOption, CaptioningNodeOptions option) {
		super(client, cortexOption, option);
		this.smolvlmClient = new SmolVLMClient(option.getSmolVLMHost(), option.getSmolVLMPort());
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
				BufferedImage image = ImageUtils.load(media.file());
				String result = smolvlmClient.captionByImage(image, 512);
				ctx.output(OUTPUT_CAPTION, result);
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

}
