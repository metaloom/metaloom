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
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
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
@NodeSpec(nodeId = "tts", name = "Text to Speech", icon = "record_voice_over", category = NodeCategory.TRANSFORM,
	description = "Speak upstream text through the /v1/tts sidecar. German routes to Orpheus/Kartoffel, "
		+ "English to Kokoro. The WAV is written to the worker's local cache; wire it into a sink to keep it.",
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor
	// advertises it. This node does, so it re-documents the inherited field here.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
		description = "Wall-clock budget per item; 0 leaves it to the worker default", min = "0", order = 9000))
// One sidecar holds one model on one device, so parallel calls only queue behind each other - hence the
// default concurrency of 1.
public class TtsNode extends AbstractMediaNode<TtsNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(TtsNode.class);

	/**
	 * The text to speak.
	 *
	 * <p>
	 * This replaces the {@code sourceNodeId}/{@code sourceOutputKey} option pair, which defaulted
	 * to {@code llm}/{@code llm_result} - an output the LLM node never wrote, since it emits one
	 * result per prompt. The node therefore synthesised nothing unless both options were
	 * hand-corrected. A declared port makes the source an edge the author draws.
	 * </p>
	 */
	@PortDoc(label = "Text", description = "The prose to speak - an LLM answer, a transcript, a caption or any other upstream text")
	public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);

	@PortDoc(label = "Audio", description = "The synthesised WAV in the worker's local cache; wire it into a sink to keep it")
	public static final OutputPort<String> OUT_AUDIO = OutputPort.one("audio", ContentTypeRegistry.ARTIFACT_AUDIO, String.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

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
		// Only processable when the text port was wired and carries something.
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
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_AUDIO, cached);
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
			ctx.output(OUT_FLAG, "DONE");
			ctx.output(OUT_AUDIO, audioPath.toString());
			resultCache.put(path, audioPath.toString());

			// The audio bytes live in the local tts_bin cache; record the ledger marker that this node produced them for the asset. Uploading the bytes
			// into the asset binary subsystem needs a byte-ingest endpoint that does not exist yet, so that remains a follow-up (same as ThumbnailNode).
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion, null);
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to synthesize audio for media {}", path, e);
			ctx.output(OUT_FLAG, "FAILED");
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion, null);
			return ctx.failure(e.getMessage()).abort();
		}
	}

	/**
	 * The wired text to synthesize, or null when the port carries nothing usable.
	 */
	private String resolveText(NodeContext<LoomMedia> ctx) {
		String text = ctx.input(IN_TEXT);
		return text == null || text.isBlank() ? null : text;
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
