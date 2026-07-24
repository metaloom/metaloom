package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

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
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.whisper.TranscriptionSegment;
import io.metaloom.cortex.media.whisper.WhisperResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class WhisperNode extends AbstractMediaNode<WhisperOptions> {

	public static final Logger log = LoggerFactory.getLogger(WhisperNode.class);

	public static final NodeOutputKey<String> OUTPUT_WHISPER_RESULT = NodeOutputKey.of("whisper_result", String.class);

	/** Upper bound for the in-heap skip cache. Transcription is expensive, so we remember the transcript JSON produced for each media during this
	 * worker's lifetime and re-emit it instead of recomputing. Non-durable - the durable copy lives in Loom. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

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
		String model = new File(options().getModelPath()).getName();

		// Re-emit a locally cached transcript instead of re-running whisper. On a hit the durable copy already exists in Loom, so we also skip
		// re-persisting.
		String cached = resultCache.get(path);
		if (cached != null) {
			ctx.output(OUTPUT_WHISPER_RESULT, cached);
			return ctx.origin(LOCAL).next();
		}

		try {
			WhisperResult result = processor.process(path);
			String json = result.toJson();
			ctx.output(OUTPUT_WHISPER_RESULT, json);
			resultCache.put(path, json);

			// Persist the transcript payload and the processing ledger entry via the Loom REST API.
			// This two-step "write typed payload -> record node result" shape is the template every persisting node follows.
			if (asset != null && client() != null) {
				try {
					TranscriptCreateRequest request = new TranscriptCreateRequest();
					request.setSource(name());
					request.setProducerVersion(model);
					request.setStreamIndex(0);
					request.setLang(options().getLanguage());
					request.setModel(model);
					request.setTranscriptText(result.segments().stream()
						.map(TranscriptionSegment::getText)
						.collect(Collectors.joining(" ")));
					if (!result.segments().isEmpty()) {
						// Segment offsets are already milliseconds, which is what the field now holds.
						request.setDuration(result.segments().get(result.segments().size() - 1).getTo());
					}
					request.setTranscriptJson(new JsonObject(json));
					UUID assetUuid = asset.getUuid();
					UUID transcriptUuid = client().createAssetTranscript(assetUuid, request).sync().body().getUuid();
					recordNodeResult(assetUuid, model, ResultState.SUCCESS, null, ctx.duration(), transcriptUuid);
				} catch (Exception e) {
					log.warn("Failed to persist transcript for asset {}: {}", asset.getUuid(), e.getMessage());
					recordNodeResult(asset.getUuid(), model, ResultState.FAILED, e.getMessage(), ctx.duration(), null);
				}
			}

			print(ctx, "DONE", result.segments().size() + " segments");
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			if (log.isErrorEnabled()) {
				log.error("Error while processing media " + path, e);
			}
			if (asset != null && client() != null) {
				recordNodeResult(asset.getUuid(), model, ResultState.FAILED, e.getMessage(), ctx.duration(), null);
			}
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Record a {@code whisper} entry in the per-asset processing ledger. Best-effort: a ledger failure is logged but never fails the node.
	 *
	 * @param assetUuid      the asset processed
	 * @param model          the producer version (whisper model)
	 * @param state          SUCCESS or FAILED
	 * @param reason         failure detail, or null on success
	 * @param durationMs     how long the node ran
	 * @param transcriptUuid the transcript row written, or null when none was written
	 */
	private void recordNodeResult(UUID assetUuid, String model, ResultState state, @Nullable String reason, long durationMs,
		@Nullable UUID transcriptUuid) {
		try {
			NodeResultCreateRequest ledger = new NodeResultCreateRequest();
			ledger.setNodeKind(name());
			ledger.setNodeId("");
			ledger.setProducerVersion(model);
			ledger.setState(state.name());
			ledger.setOrigin(ResultOrigin.COMPUTED.name());
			ledger.setReason(reason);
			ledger.setDurationMs(durationMs);
			if (transcriptUuid != null) {
				ledger.setResultRef(new JsonObject()
					.put("table", "asset_transcript_comp")
					.put("uuids", new JsonArray().add(transcriptUuid.toString())));
			}
			client().createAssetNodeResult(assetUuid, ledger).sync();
		} catch (Exception e) {
			log.warn("Failed to record node result for asset {}: {}", assetUuid, e.getMessage());
		}
	}
}
