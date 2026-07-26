package io.metaloom.cortex.node.tts;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Text-to-speech node. Unlike the analysis nodes it does not annotate the media
 * with a property of the media itself - it <em>generates</em> narration audio
 * from text produced by an upstream node (e.g. an LLM summary or a translated
 * transcript) and attaches it to the asset.
 *
 * <p>
 * The synthesis runs in the FastAPI {@code /v1/tts} sidecar (see the module's
 * {@code server/} directory); this node is a pure HTTP client. German routes to
 * Orpheus/Kartoffel, English to Kokoro, selected by {@link TtsNodeOptions#getLanguage()}.
 * </p>
 *
 * <p>
 * Following the {@code ThumbnailNode} pattern, the generated WAV is written to a
 * local cache under {@code metaPath/tts_bin} and only the {@code asset_node_result}
 * ledger entry is recorded in Loom - the bytes stay local (there is no
 * byte-ingest endpoint for produced media yet).
 * </p>
 */
public class TtsNode extends AbstractMediaNode<TtsNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TtsNode.class);

	public static final NodeOutputKey<String> OUTPUT_TTS_FLAG = NodeOutputKey.of("tts_flag", String.class);
	public static final NodeOutputKey<String> OUTPUT_TTS_PATH = NodeOutputKey.of("tts_path", String.class);

	/** In-heap skip cache of the generated audio path, keyed by media path, to avoid re-synthesizing within this worker's lifetime. The rendered WAV
	 * itself is a durable local artifact under {@code metaPath/tts_bin}. */
	private static final int RESULT_CACHE_SIZE = 10_000;

	private final LocalResultCache<String> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final TtsClient ttsClient;
	private final CortexOptions cortexOptions;

	@Inject
	public TtsNode(@Nullable LoomClient client, CortexOptions cortexOptions, TtsNodeOptions options, TtsClient ttsClient) {
		super(client, cortexOptions, options);
		this.cortexOptions = cortexOptions;
		this.ttsClient = ttsClient;
	}

	@Override
	public String name() {
		return "tts";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		// Only processable when an upstream node supplied text to speak.
		return resolveText(ctx) != null;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();
		String producerVersion = options().getLanguage() + ":" + options().getVoice();

		String text = resolveText(ctx);
		if (text == null) {
			return ctx.skipped("no upstream text").next();
		}

		// Re-emit a locally cached audio path instead of re-synthesizing. On a hit the ledger entry already exists in Loom, so we also skip re-persisting.
		String cached = resultCache.get(path);
		if (cached != null) {
			metrics.recordAiCacheHit("tts");
			ctx.output(OUTPUT_TTS_FLAG, "DONE");
			ctx.output(OUTPUT_TTS_PATH, cached);
			return ctx.origin(LOCAL).next();
		}

		try {
			long aiStart = System.currentTimeMillis();
			byte[] wav;
			try {
				wav = ttsClient.synthesize(text, options().getLanguage(), options().getVoice());
			} catch (RuntimeException e) {
				metrics.recordAiCall("tts", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("tts", true, System.currentTimeMillis() - aiStart);

			Path audioPath = resolveAudioPath(media);
			Files.createDirectories(audioPath.getParent());
			Files.write(audioPath, wav);

			ctx.print("DONE", wav.length + " bytes");
			ctx.output(OUTPUT_TTS_FLAG, "DONE");
			ctx.output(OUTPUT_TTS_PATH, audioPath.toString());
			resultCache.put(path, audioPath.toString());

			// The audio bytes live in the local tts_bin cache; record the ledger marker that this node produced them for the asset. Uploading the bytes
			// into the asset binary subsystem needs a byte-ingest endpoint that does not exist yet, so that remains a follow-up (same as ThumbnailNode).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion, null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to synthesize audio for media {}", path, e);
			ctx.output(OUTPUT_TTS_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion, null);
			return ctx.failure(e.getMessage()).next();
		}
	}

	/**
	 * Resolve the upstream text to synthesize from the configured source node output, or null when it is absent or blank.
	 */
	private String resolveText(NodeContext<LoomMedia> ctx) {
		Object value = ctx.upstreamOutput(options().getSourceNodeId(), options().getSourceOutputKey());
		if (value == null) {
			return null;
		}
		String text = value.toString();
		return text.isBlank() ? null : text;
	}

	/**
	 * Resolve the local cache path for the generated WAV: {@code metaPath/tts_bin/<segment>/<sha512>.wav}. Mirrors {@code ThumbnailNode}.
	 */
	private Path resolveAudioPath(LoomMedia media) {
		SHA512 hash = media.getSHA512();
		String fileName = hash + ".wav";
		Path basePath = cortexOptions.getMetaPath().resolve("tts_bin");
		Path dirPath = HashUtils.segmentPath(basePath, hash);
		return dirPath.resolve(fileName);
	}
}
