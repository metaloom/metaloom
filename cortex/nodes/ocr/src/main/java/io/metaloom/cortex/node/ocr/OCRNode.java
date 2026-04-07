package io.metaloom.cortex.node.ocr;

import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class OCRNode extends AbstractMediaNode<Void, OCRNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(OCRNode.class);

	@Inject
	public OCRNode(@Nullable LoomClient client, CortexOptions options, OCRNodeOptions actionOptions) {
		super(client, options, actionOptions);
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
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		return ctx.skipped("not implemented").next();
	}

}
