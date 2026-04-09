package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;
import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

public class WhisperNode extends AbstractMediaNode<WhisperOptions> {

	public static final Logger log = LoggerFactory.getLogger(WhisperNode.class);

	public static final NodeOutputKey<String> OUTPUT_WHISPER_RESULT = NodeOutputKey.of("whisper_result", String.class);

	public static final LoomMetaKey<String> WHISPER_FLAG_KEY = metaKey("whisper-result", 1, FS, String.class);

	private WhisperMediaProcessor processor;

	@Inject
	public WhisperNode(@Nullable LoomClient client, CortexOptions cortexOptions, WhisperOptions options, WhisperMediaProcessor processor) {
		super(client, cortexOptions, options);
		this.processor = processor;
	}

	@Override
	public String name() {
		return "whisper";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (options().isEnabled()) {
			return ctx.media().isVideo() || ctx.media().isAudio();
		} else {
			return false;
		}
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		try {
			WhisperResult result = processor.process(path);
			String json = result.toJson();
			ctx.output(OUTPUT_WHISPER_RESULT, json);
			print(ctx, "DONE", result.segments().size() + " segments");
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			if (log.isErrorEnabled()) {
				log.error("Error while processing media " + path, e);
			}
			return ctx.failure(e.getMessage()).next();
		}
	}
}
