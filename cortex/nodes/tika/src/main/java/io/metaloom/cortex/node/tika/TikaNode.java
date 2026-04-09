package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.XATTR;
import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.node.payload.TextPayload;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class TikaNode extends AbstractMediaNode<TikaNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TikaNode.class);

	public static final NodeOutputKey<String> OUTPUT_TIKA_FLAGS = NodeOutputKey.of("tika_flags", String.class);
	public static final NodeOutputKey<String> OUTPUT_TIKA_CONTENT = NodeOutputKey.of("tika_content", String.class);

	public static final LoomMetaKey<String> TIKA_FLAGS_KEY = metaKey("tika_flags", 1, XATTR, String.class);


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
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Error while processing media " + media.path(), e);
			ctx.output(OUTPUT_TIKA_FLAGS, "FAILED");
			return ctx.failure("failed processing").next();
		}
	}

}
