package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.node.tika.TikaMedia.TIKA;
import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class TikaNode extends AbstractMediaNode<Void, TikaNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TikaNode.class);

	public static String DONE_FLAG = "DONE";

	public static String NULL_FLAG = "NULL";

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
	protected boolean isProcessed(NodeContext<LoomMedia> ctx) {
		TikaMedia media = ctx.media().of(TIKA);
		return media.getTikaFlags() != null;
	}

	@Override
	protected NodeResult<Void> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		TikaMedia media = ctx.media(TIKA);
		String flags = getFlags(media);
		if (NULL_FLAG.equals(flags)) {
			return ctx.skipped("previously failed").next();
		} else if (flags == null) {
			if (asset == null) {
				return parseMedia(ctx);
				// TODO utilize and store result
			} else {
				// TODO check whether db needs tika update
				return parseMedia(ctx);
				// TODO check response and assert whether processing is needed
				// return done(media, start, "from db");
				// writeFlags(media, DONE_FLAG);
			}
		} else {
			return ctx.info("already processed").next();
		}
	}

	private NodeResult<Void> parseMedia(NodeContext<LoomMedia> ctx) {
		TikaMedia media = ctx.media(TIKA);
		try {
			String result = MediaTikaParser.parse(media);
			// TODO store result
			media.setTikaFlags(DONE_FLAG);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Error while processing media " + media.path(), e);
			media.setTikaFlags(DONE_FLAG);
			return ctx.failure("failed processing").next();
		}
	}

	private String getFlags(TikaMedia media) {
		String tikaFlags = media.getTikaFlags();
		if (tikaFlags == null) {
			// media.setTikaFlags(tikaFlags);
		}
		return tikaFlags;
	}

}
