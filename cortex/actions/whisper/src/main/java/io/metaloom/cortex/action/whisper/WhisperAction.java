package io.metaloom.cortex.action.whisper;

import static io.metaloom.cortex.api.action.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.media.whisper.WhisperMedia.WHISPER;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.action.ActionResult;
import io.metaloom.cortex.api.action.context.ActionContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.action.AbstractMediaAction;
import io.metaloom.cortex.media.whisper.WhisperMedia;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;

@Singleton
public class WhisperAction extends AbstractMediaAction<WhisperOptions> {

	public static final Logger log = LoggerFactory.getLogger(WhisperAction.class);

	private WhisperMediaProcessor processor;

	@Inject
	public WhisperAction(@Nullable LoomClient client, CortexOptions cortexOptions, WhisperOptions options, WhisperMediaProcessor processor) {
		super(client, cortexOptions, options);
		this.processor = processor;
	}

	@Override
	public String name() {
		return "whisper";
	}

	@Override
	protected boolean isProcessable(ActionContext ctx) {
		if (options().isEnabled()) {
			return ctx.media().isVideo() || ctx.media().isAudio();
		} else {
			return false;
		}
	}

	@Override
	protected boolean isProcessed(ActionContext ctx) {
		WhisperMedia media = ctx.media(WHISPER);
		return media.hasWhisper();
	}

	@Override
	protected ActionResult compute(ActionContext ctx, AssetResponse asset) throws Exception {
		WhisperMedia media = ctx.media(WHISPER);
		String path = media.absolutePath();

		try {
			WhisperResult result = processor.process(path);
			media.setWhisperResult(result);
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
