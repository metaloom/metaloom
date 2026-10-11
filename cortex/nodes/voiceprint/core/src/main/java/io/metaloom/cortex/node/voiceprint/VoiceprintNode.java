package io.metaloom.cortex.node.voiceprint;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;
import static io.metaloom.cortex.api.node.ResultOrigin.LOCAL;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

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
import io.metaloom.cortex.api.node.spec.PortGroupDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.cache.LocalResultCache;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.person.PersonSuggestions;
import io.metaloom.cortex.common.person.PersonSuggestions.Suggestion;
import io.metaloom.cortex.common.person.PersonSuggestions.UnusableSampleException;
import io.metaloom.cortex.node.voiceprint.VoiceprintResult.Segment;
import io.metaloom.cortex.node.voiceprint.VoiceprintResult.Speaker;
import io.metaloom.cortex.node.voiceprint.VoiceprintResult.Window;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterMemberCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.person.PersonPendingSample;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Voiceprint node: who speaks when, and what each voice sounds like as a vector.
 *
 * <p>
 * The audio analog of {@code facedetect}. The model runs in the FastAPI {@code /v1/voiceprints} sidecar ({@code sidecars/voiceprint}: ReDimNet2
 * by default, WeSpeaker or SpeechBrain by option); this node is a pure HTTP client. It hands the sidecar the asset's audio - a video is first
 * demuxed to a 16 kHz WAV with {@code ffmpeg} so a worker never uploads the pictures - and gets back every speech window as a unit vector, the
 * windows grouped into speakers with a timeline, and one pooled voiceprint for the whole file.
 * </p>
 *
 * <p>
 * Persistence mirrors the face workflow so the same review and person-assignment loop applies: one {@code embedding} row per window
 * ({@code type=voiceprint}, with {@code timeFrom}/{@code timeTo} saying when the voice was heard), one {@code cluster} per speaker
 * ({@code type=speaker}, centroid = the speaker's voiceprint) holding those windows as members, and an {@code asset_json_comp}
 * ({@code schemaType=voiceprint}) carrying the timeline without the vectors. The ledger points at the component.
 * </p>
 *
 * <p>
 * <strong>Person suggestions.</strong> Before the speakers are written, each one's voiceprint is matched against the persons whose voices are known -
 * speaker clusters a reviewer confirmed, and the voice samples uploaded to or imported into a person - and the closest person above the model's
 * threshold is stored as the cluster's <em>suggestion</em>. Samples that have no print in this node's model yet are embedded first, with the same
 * sidecar and model, so an uploaded sample takes effect on the next item. A suggestion is never a confirmation: the cluster stays PENDING until a
 * reviewer decides.
 * </p>
 */
@NodeSpec(nodeId = "voiceprint", name = "Voiceprint (Speaker Embeddings)", icon = "record_voice_over", category = NodeCategory.ANALYSIS,
	description = "Find the speakers in audio or video speech and compute a voiceprint (speaker embedding) per speaker and per speech window.",
	inputGroups = @PortGroupDoc(id = "media_alt", mode = PortGroupMode.XOR, label = "Media"),
	// timeoutMs lives on AbstractNodeOptions, where it is hidden because almost no descriptor advertises it. A long recording makes it matter
	// here, so this node re-documents the inherited field.
	parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
		description = "Wall-clock budget per item for the demux and the sidecar call; 0 means 10 minutes", min = "0", order = 9000))
public class VoiceprintNode extends AbstractMediaNode<VoiceprintNodeOptions> {

	public static final Logger log = LoggerFactory.getLogger(VoiceprintNode.class);

	/** The two alternatives of the descriptor's {@code media_alt} XOR group - one input, two shapes. */
	@PortDoc(label = "Audio", description = "An audio file whose speakers are to be found", group = "media_alt")
	public static final InputPort<LoomMedia> IN_AUDIO = InputPort.one("audio", ContentTypeRegistry.MEDIA_AUDIO, LoomMedia.class);

	@PortDoc(label = "Video", description = "A video whose audio track is demuxed and analysed", group = "media_alt")
	public static final InputPort<LoomMedia> IN_VIDEO = InputPort.one("video", ContentTypeRegistry.MEDIA_VIDEO, LoomMedia.class);

	@PortDoc(label = "Speaker Count", description = "How many distinct voices were found")
	public static final OutputPort<Long> OUT_SPEAKER_COUNT = OutputPort.one("speaker_count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Speakers", description = "One element per speaker, longest first: its index, when it speaks, and its voiceprint vector")
	public static final OutputPort<String> OUT_SPEAKERS = OutputPort.many("speakers", ContentTypeRegistry.STRUCT_EMBEDDING, String.class);

	@PortDoc(label = "Voiceprint", description = "One pooled voiceprint over all speech in the item - the vector to enrol a single-speaker recording with")
	public static final OutputPort<String> OUT_VOICEPRINT = OutputPort.one("voiceprint", ContentTypeRegistry.STRUCT_EMBEDDING, String.class);

	@PortDoc(label = "Timeline", description = "The speaker turns as time segments, each labelled with its speaker")
	public static final OutputPort<String> OUT_TIMELINE = OutputPort.one("timeline", ContentTypeRegistry.STRUCT_SEGMENTS, String.class);

	@PortDoc(label = "Result", description = "The full analysis without the vectors: model, thresholds, speakers, windows")
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_JSON, String.class);

	/**
	 * Who the speakers are, as far as anybody knows: per speaker cluster, the person a reviewer confirmed or the person matching suggested. Always
	 * written - an empty array when nobody was recognised - so a {@code filterBy=PERSON} filter wired to it waits for this node and then runs.
	 */
	@PortDoc(label = "Persons", description = "The known persons recognised among the speakers - confirmed by a reviewer or suggested - for a "
		+ "person filter to run after")
	public static final OutputPort<String> OUT_PERSONS = OutputPort.one("persons", ContentTypeRegistry.STRUCT_PERSONS, String.class);

	/** Changes whenever the meaning of the persisted rows changes; the model id is appended per run. */
	public static final String PRODUCER = "voiceprint/1";

	/** In-heap skip cache of the sidecar's answer, keyed by the file's identity plus the options that change the result (see {@link #cacheKey}). */
	private static final int RESULT_CACHE_SIZE = 2_000;

	private final LocalResultCache<VoiceprintResult> resultCache = new LocalResultCache<>(RESULT_CACHE_SIZE);

	private final VoiceprintClient voiceprintClient;

	@Inject
	public VoiceprintNode(@Nullable LoomClient client, CortexOptions cortexOptions, VoiceprintNodeOptions options, VoiceprintClient voiceprintClient) {
		super(client, cortexOptions, options);
		this.voiceprintClient = voiceprintClient;
	}

	/**
	 * Who the speakers are is a reviewer's decision, and it may have changed since the speakers were found - so a replayed output reads the
	 * {@code persons} port from Loom again instead of handing on the stored one.
	 */
	@Override
	protected Map<String, Object> refreshOnReuse(AssetResponse asset) {
		return Map.of(OUT_PERSONS.id(), PersonSuggestions.personsPayload(currentSpeakerClusters(asset)).encode());
	}

	@Override
	public String name() {
		return "voiceprint";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (!options().isEnabled()) {
			return false;
		}
		return ctx.media().isVideo() || ctx.media().isAudio();
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		String path = media.absolutePath();

		// Re-emit a locally cached analysis instead of calling the sidecar again. On a hit the durable copy already exists in Loom, so we also
		// skip re-persisting.
		String cacheKey = cacheKey(media);
		VoiceprintResult cached = resultCache.get(cacheKey);
		if (cached != null) {
			metrics.recordAiCacheHit("voiceprint");
			emit(ctx, cached);
			// The analysis is cached; who the speakers are is not - a reviewer may have confirmed one since. Loom has the current answer.
			emitPersons(ctx, currentSpeakerClusters(asset));
			return ctx.origin(LOCAL).next();
		}

		String producerVersion = PRODUCER;
		Path demuxed = null;
		try {
			Path audio = media.path();
			if (media.isVideo()) {
				// The sidecar could decode the video itself, but a worker should not upload gigabytes of pictures to have them thrown away.
				demuxed = Files.createTempFile("voiceprint-", ".wav");
				new AudioDemuxer(options().getFfmpegPath()).toWav(audio, demuxed, options().getTimeoutMs());
				audio = demuxed;
			}

			long aiStart = System.currentTimeMillis();
			VoiceprintResult result;
			try {
				result = voiceprintClient.analyze(audio, params(), options().getTimeoutMs());
			} catch (RuntimeException e) {
				metrics.recordAiCall("voiceprint", false, System.currentTimeMillis() - aiStart);
				throw e;
			}
			metrics.recordAiCall("voiceprint", true, System.currentTimeMillis() - aiStart);
			producerVersion = producerVersion(result);

			if (!result.hasSpeech()) {
				// Nothing to persist: a silent clip did not need processing. The ledger still records the verdict so a reviewer can tell
				// "no speech" from "never ran".
				if (asset != null && client() != null) {
					recordNodeResult(asset, ctx, ResultState.SKIPPED, "no speech found", producerVersion, null);
				}
				emitPersons(ctx, List.of());
				return ctx.skipped("no speech found").next();
			}

			emit(ctx, result);
			resultCache.put(cacheKey, result);
			ctx.print("DONE", result.speakers().size() + " speaker(s), " + result.windows().size() + " window(s)");
			emitPersons(ctx, persist(ctx, asset, result, producerVersion));
			return ctx.origin(COMPUTED).next();
		} catch (Exception e) {
			log.error("Failed to compute voiceprints for media {}", path, e);
			if (asset != null && client() != null) {
				recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion, null);
			}
			return ctx.failure(e.getMessage()).abort();
		} finally {
			if (demuxed != null) {
				Files.deleteIfExists(demuxed);
			}
		}
	}

	/**
	 * The request fields from the options. A zero threshold and a blank model are "the sidecar's defaults", not literal values.
	 */
	VoiceprintClient.Params params() {
		VoiceprintNodeOptions o = options();
		return new VoiceprintClient.Params(o.resolvedModel(), o.getWindowSeconds(), o.getHopSeconds(), o.getMinSpeechSeconds(),
			o.getClusterThreshold() == 0.0 ? null : o.getClusterThreshold(), o.getMaxSpeakers() == 0 ? null : o.getMaxSpeakers());
	}

	static String producerVersion(VoiceprintResult result) {
		return PRODUCER + ":" + result.model();
	}

	/**
	 * Emit the five node outputs and the debug preview.
	 */
	private void emit(NodeContext<LoomMedia> ctx, VoiceprintResult result) {
		ctx.output(OUT_SPEAKER_COUNT, (long) result.speakers().size());
		for (Speaker speaker : result.speakers()) {
			ctx.outputElement(OUT_SPEAKERS, VoiceprintResult.speakerJson(speaker).encode());
		}
		if (result.pooled() != null) {
			ctx.output(OUT_VOICEPRINT, new JsonObject().put("model", result.model()).put("dim", result.dim())
				.put("vector", VoiceprintResult.toJson(result.pooled())).encode());
		}
		ctx.output(OUT_TIMELINE, result.toTimelineJson().encode());
		ctx.output(OUT_RESULT, result.toSummaryJson().encode());
		ctx.preview(OUT_TIMELINE, speakersMarkdown(result));
	}

	/**
	 * A GFM table of the speakers for the editor's debug view: who, how long, how many turns, how tight the cluster. Never throws - a preview
	 * must not be able to fail the node.
	 */
	static String speakersMarkdown(VoiceprintResult result) {
		try {
			StringBuilder sb = new StringBuilder("| speaker | speaking time | turns | cohesion | first heard |\n|---|---|---|---|---|\n");
			for (Speaker s : result.speakers()) {
				long first = s.segments().isEmpty() ? 0 : s.segments().get(0).startMs();
				sb.append("| ").append(s.index() + 1)
					.append(" | ").append(clock(s.totalMs()))
					.append(" | ").append(s.segments().size())
					.append(" | ").append(String.format(Locale.ROOT, "%.2f", s.cohesion()))
					.append(" | ").append(clock(first)).append(" |\n");
			}
			sb.append("\n").append(result.model()).append(" - ").append(clock(result.speechMs())).append(" of speech in ")
				.append(clock(result.durationMs())).append(", ").append(result.windows().size()).append(" windows");
			return sb.toString();
		} catch (RuntimeException e) {
			return result.speakers().size() + " speaker(s)";
		}
	}

	static String clock(long ms) {
		long s = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
	}

	/**
	 * The three writes, then the ledger. Each is best-effort in the facedetect sense: an embedding batch that fails leaves nothing broken, and a
	 * re-run upserts every row rather than duplicating it. A failure anywhere is still reported on the ledger as FAILED.
	 */
	private List<ClusterResponse> persist(NodeContext<LoomMedia> ctx, AssetResponse asset, VoiceprintResult result, String producerVersion) {
		if (asset == null || client() == null) {
			return List.of();
		}
		List<ClusterResponse> clusters = List.of();
		try {
			List<UUID> embeddingUuids = persistEmbeddings(asset, result, producerVersion);
			if (options().isPersistWindows()) {
				clusters = persistClusters(asset, result, embeddingUuids, producerVersion);
			}
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(VoiceprintNodeOptions.EMBEDDING_TYPE);
			// One timeline per asset per node kind.
			request.setVariant("");
			request.setProducerVersion(producerVersion);
			request.setData(result.toSummaryJson());
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, ResultState.SUCCESS, null, producerVersion, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("Failed to persist voiceprints for asset {}: {}", asset.getUuid(), e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), producerVersion, null);
		}
		return clusters;
	}

	/** The {@code persons} port: who the speaker clusters name. Written on every path that ran, so a person filter wired to it is never starved. */
	private void emitPersons(NodeContext<LoomMedia> ctx, List<ClusterResponse> clusters) {
		ctx.output(OUT_PERSONS, PersonSuggestions.personsPayload(clusters).encode());
	}

	/** This node's speaker clusters for the asset as Loom holds them now, or none when Loom is out of reach. */
	private List<ClusterResponse> currentSpeakerClusters(AssetResponse asset) {
		if (asset == null || client() == null) {
			return List.of();
		}
		try {
			List<ClusterResponse> clusters = client().listAssetClusters(asset.getUuid()).sync().body().getData();
			return clusters == null ? List.of()
				: clusters.stream().filter(c -> VoiceprintNodeOptions.CLUSTER_TYPE.equals(c.getType()) && name().equals(c.getNodeKind())).toList();
		} catch (Exception e) {
			log.debug("Could not read the speaker clusters of asset {}: {}", asset.getUuid(), e.getMessage());
			return List.of();
		}
	}

	/**
	 * Who each speaker most resembles among the persons whose voices are known, embedding any of their samples this model has not seen yet. Null
	 * entries for speakers that resemble nobody closely enough; all null when suggestions are off.
	 */
	List<Suggestion> suggest(VoiceprintResult result) {
		List<float[]> centroids = result.speakers().stream().map(Speaker::vector).toList();
		if (!options().isSuggestPersons()) {
			return new ArrayList<>(Collections.nCopies(centroids.size(), (Suggestion) null));
		}
		return PersonSuggestions.suggest(client(), VoiceprintNodeOptions.CLUSTER_TYPE, result.model(), centroids,
			options().resolvedSuggestThreshold(result.model()), this::enrol);
	}

	/**
	 * One of a person's voice samples as a voiceprint in this node's model: the pooled vector over all its speech.
	 *
	 * <p>
	 * Always decoded to WAV first, whatever was uploaded - an MP3 from a phone, a video of somebody speaking - so the sidecar sees exactly the input it
	 * sees for an item. A sample with no speech, or that ffmpeg cannot decode at all, is unusable and recorded as such; a sidecar or ffmpeg that is
	 * merely unavailable is not, so the sample is tried again on a later item.
	 * </p>
	 */
	float[] enrol(PersonPendingSample sample, Path file) throws Exception {
		Path wav = Files.createTempFile("voiceprint-sample-", ".wav");
		try {
			try {
				new AudioDemuxer(options().getFfmpegPath()).toWav(file, wav, options().getTimeoutMs());
			} catch (IOException e) {
				if (e.getMessage() != null && e.getMessage().startsWith("ffmpeg is not available")) {
					throw e;
				}
				throw new UnusableSampleException("not decodable as audio");
			}
			VoiceprintResult enrolled = voiceprintClient.analyze(wav, params(), options().getTimeoutMs());
			if (!enrolled.hasSpeech() || enrolled.pooled() == null) {
				throw new UnusableSampleException("no speech found");
			}
			return enrolled.pooled();
		} finally {
			Files.deleteIfExists(wav);
		}
	}

	/**
	 * One {@code embedding} row per speech window (the default), or one per speaker when {@code persistWindows} is off. Either way the row says
	 * when the voice was heard: a window's own bounds, or the speaker's first and last segment.
	 *
	 * @return the uuids of the stored rows, in window order (or speaker order); null entries where the server returned nothing usable
	 */
	private List<UUID> persistEmbeddings(AssetResponse asset, VoiceprintResult result, String producerVersion) throws Exception {
		List<EmbeddingCreateRequest> items = new ArrayList<>();
		if (options().isPersistWindows()) {
			for (Window w : result.windows()) {
				items.add(embedding(result, producerVersion, w.vector(), w.index(), w.startMs(), w.endMs()));
			}
		} else {
			for (Speaker s : result.speakers()) {
				long from = s.segments().isEmpty() ? 0 : s.segments().get(0).startMs();
				long to = s.segments().isEmpty() ? 0 : s.segments().get(s.segments().size() - 1).endMs();
				items.add(embedding(result, producerVersion, s.vector(), s.index(), from, to).setConfidence((float) s.cohesion()));
			}
		}
		List<UUID> uuids = new ArrayList<>(Collections.nCopies(items.size(), (UUID) null));
		if (items.isEmpty()) {
			return uuids;
		}
		EmbeddingBulkResponse response = client()
			.bulkCreateAssetEmbeddings(asset.getUuid(), new EmbeddingBulkCreateRequest().setEmbeddings(items))
			.sync()
			.body();
		List<EmbeddingResponse> rows = response == null ? null : response.getEmbeddings();
		if (rows != null && rows.size() == items.size()) {
			for (int i = 0; i < rows.size(); i++) {
				uuids.set(i, rows.get(i).getUuid());
			}
		} else {
			// Without the uuids there is nothing to build cluster membership from. The embeddings are stored either way; only the grouping is
			// lost, and a re-run rebuilds it.
			log.warn("Stored embeddings for asset {} but the response carried no usable uuids; speaker clusters will have no members", asset.getUuid());
		}
		log.info("Stored {} voiceprint embedding(s) for asset {}", items.size(), asset.getUuid());
		return uuids;
	}

	private EmbeddingCreateRequest embedding(VoiceprintResult result, String producerVersion, float[] vector, int subjectIndex, long from, long to) {
		return new EmbeddingCreateRequest()
			.setType(VoiceprintNodeOptions.EMBEDDING_TYPE)
			.setNodeKind(name())
			// The sidecar's full model id: part of the row's identity and of the vector-index key, so two backends' vectors never share a space.
			.setModel(result.model())
			.setProducerVersion(producerVersion)
			.setVector(boxed(vector))
			.setDimensions(vector == null ? null : vector.length)
			.setFrameNumber(0)
			.setSubjectIndex(subjectIndex)
			.setNormalized(true)
			.setTimeFrom(from)
			.setTimeTo(to);
	}

	/**
	 * One {@code cluster} per speaker, its members the windows attributed to it, each member's confidence the window's cosine to the speaker's
	 * voiceprint. Same shape as a face cluster, so the review queue and person assignment apply unchanged.
	 */
	private List<ClusterResponse> persistClusters(AssetResponse asset, VoiceprintResult result, List<UUID> embeddingUuids, String producerVersion)
		throws Exception {
		if (result.speakers().isEmpty()) {
			return List.of();
		}
		List<Suggestion> suggestions = suggest(result);
		ClusterBulkCreateRequest request = new ClusterBulkCreateRequest();
		for (int s = 0; s < result.speakers().size(); s++) {
			Speaker speaker = result.speakers().get(s);
			Suggestion suggestion = s < suggestions.size() ? suggestions.get(s) : null;
			JsonArray segments = new JsonArray();
			for (Segment g : speaker.segments()) {
				segments.add(new JsonObject().put("startMs", g.startMs()).put("endMs", g.endMs()));
			}
			ClusterCreateItem item = new ClusterCreateItem()
				.setType(VoiceprintNodeOptions.CLUSTER_TYPE)
				.setNodeKind(name())
				.setProducerVersion(producerVersion)
				.setClusterIndex(speaker.index())
				.setScore((float) speaker.cohesion())
				.setCentroid(boxed(speaker.vector()))
				.setModel(result.model())
				.setDimensions(speaker.vector() == null ? null : speaker.vector().length)
				.setMeta(new JsonObject().put("totalMs", speaker.totalMs()).put("segments", segments));
			if (suggestion != null) {
				item.setSuggestedPersonUuid(suggestion.personUuid().toString()).setSuggestedScore(suggestion.score());
			}
			for (int windowIndex : speaker.windows()) {
				UUID embeddingUuid = windowIndex < embeddingUuids.size() ? embeddingUuids.get(windowIndex) : null;
				if (embeddingUuid == null) {
					continue;
				}
				float[] window = result.windows().get(windowIndex).vector();
				item.add(new ClusterMemberCreateItem()
					.setEmbeddingUuid(embeddingUuid.toString())
					.setConfidence(cosine(window, speaker.vector()))
					.setOrigin("AUTO"));
			}
			request.add(item);
		}
		ClusterBulkResponse stored = client().bulkCreateAssetClusters(asset.getUuid(), request).sync().body();
		if (stored == null || stored.getClusters() == null) {
			return List.of();
		}
		log.info("Stored {} speaker cluster(s) for asset {} ({} stale proposal(s) retired, {} suggested)", stored.getClusters().size(),
			asset.getUuid(), stored.getPruned(), suggestions.stream().filter(Objects::nonNull).count());
		return stored.getClusters();
	}

	static float cosine(float[] a, float[] b) {
		if (a == null || b == null || a.length != b.length || a.length == 0) {
			return 0f;
		}
		double dot = 0, na = 0, nb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return na == 0 || nb == 0 ? 0f : (float) (dot / Math.sqrt(na * nb));
	}

	private static Float[] boxed(float[] vector) {
		if (vector == null) {
			return null;
		}
		Float[] out = new Float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			out[i] = vector[i];
		}
		return out;
	}

	/**
	 * Cache key covering everything that changes the result: the file's identity (path, size, mtime - a path alone replays the first answer for a
	 * file rewritten in place, see the hash node's {@code MediaCacheKey}) and the options the sidecar sees.
	 */
	private String cacheKey(LoomMedia media) {
		String path = media.absolutePath();
		File file = new File(path);
		VoiceprintNodeOptions o = options();
		int optionsHash = Objects.hash(o.resolvedModel(), o.getWindowSeconds(), o.getHopSeconds(), o.getMinSpeechSeconds(), o.getClusterThreshold(),
			o.getMaxSpeakers());
		return path + "|" + file.length() + "|" + file.lastModified() + "|" + Integer.toHexString(optionsHash);
	}
}
