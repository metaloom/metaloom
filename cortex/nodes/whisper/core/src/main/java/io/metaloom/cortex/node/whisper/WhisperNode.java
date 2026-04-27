package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.io.File;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.whisper.TranscriptionSegment;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.vertx.core.json.JsonObject;

public class WhisperNode extends AbstractMediaNode<WhisperOptions> {

	public static final Logger log = LoggerFactory.getLogger(WhisperNode.class);

	public static final NodeOutputKey<String> OUTPUT_WHISPER_RESULT = NodeOutputKey.of("whisper_result", String.class);

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

			// Persist transcript via Loom REST API
			if (asset != null) {
				try {
					TranscriptCreateRequest request = new TranscriptCreateRequest();
					request.setSource("whisper");
					request.setLang(options().getLanguage());
					request.setModel(new File(options().getModelPath()).getName());
					request.setTranscriptText(result.segments().stream()
						.map(TranscriptionSegment::getText)
						.collect(Collectors.joining(" ")));
					if (!result.segments().isEmpty()) {
						long lastTo = result.segments().get(result.segments().size() - 1).getTo();
						request.setDuration((int) lastTo);
					}
					request.setTranscriptJson(new JsonObject(json));
					UUID assetUuid = asset.getUuid();
					client().createAssetTranscript(assetUuid, request).sync();
				} catch (Exception e) {
					log.warn("Failed to persist transcript for asset {}: {}", asset.getUuid(), e.getMessage());
				}
			}

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
