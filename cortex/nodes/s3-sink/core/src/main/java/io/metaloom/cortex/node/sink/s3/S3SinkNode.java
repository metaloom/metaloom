package io.metaloom.cortex.node.sink.s3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.Element;
import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.cortex.node.sink.s3.UploadedArtifact.State;
import io.metaloom.cortex.s3.S3ContentTypes;
import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.cortex.s3.S3Uri;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Uploads files produced by upstream nodes into an S3 bucket and registers them in Loom.
 *
 * <p>Five nodes produce bytes today - {@code thumbnail}, {@code depthmap}, {@code imagegen},
 * {@code tts} and {@code script} - and each writes into a worker-local {@code metaPath/*_bin}
 * cache with a ledger-only row, so the result exists only on the machine that made it. This node
 * is what makes those bytes durable and retrievable.</p>
 *
 * <p>Per artifact, in this order:</p>
 * <ol>
 * <li><b>SHA-512 the local file.</b> {@code asset.sha512sum} is {@code NOT NULL UNIQUE}, so an
 * asset cannot exist before its bytes are hashed.</li>
 * <li><b>Upload</b> at a deterministic, content-addressed key.</li>
 * <li><b>Create the asset</b> for the artifact, with {@code origin} set to the {@code s3://} URI -
 * the value the {@code asset.initial_origin} column comment explicitly sanctions.</li>
 * </ol>
 *
 * <p>The source asset separately receives an {@code asset_json_comp}
 * ({@code schemaType = s3-artifact}, {@code variant} = this node's id) indexing everything this
 * sink published for it, plus the usual {@code asset_node_result} ledger row.</p>
 *
 * <h2>🔴 The sink must run on the same worker as its producer</h2>
 *
 * <p>It reads files the producing node wrote to local disk. Dispatched elsewhere it would see
 * nothing - and a sink that uploads nothing looks like success, which is the failure mode this
 * class works hardest to avoid: an upstream output that names a file <em>not present on this
 * worker</em> fails the node loudly rather than being skipped. Until affinity groups exist, pin
 * producer and sink together with {@code CORTEX_NODE_WHITELIST}.</p>
 */
@NodeSpec(nodeId = "s3-sink", name = "S3 Sink", icon = "cloud_upload", category = NodeCategory.OUTPUT,
	description = "Uploads files produced by upstream nodes - thumbnails, depth maps, generated "
		+ "images, speech audio - into an S3 bucket, and registers each one in Loom as an asset "
		+ "so it becomes retrievable rather than living on a single worker's disk.",
	defaultBlocking = false,
	// A sink runs at the end of a branch and has never advertised the two retry knobs - the descriptor
	// listed only `enabled`. The base class leaves them visible, so they are dropped here.
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true) })
public class S3SinkNode extends AbstractMediaNode<S3SinkNodeOptions> implements PipelineConfigurable {

	public static final String KIND = "s3-sink";

	/** Component discriminator on the source asset. */
	public static final String SCHEMA_TYPE = "s3-artifact";

	/**
	 * The files to publish, one element per file.
	 *
	 * <p>
	 * This replaces the {@code artifacts} option ({@code nodeId:outputKey} strings) and the
	 * {@code autoDiscover} flag (anything whose key ends in {@code _path}). Both were workarounds
	 * for having no way to say "this file, from that node" in the graph: the option broke when a
	 * node was renamed, and discovery-by-suffix silently missed every {@code script} image, because
	 * those output keys are author-named. An edge into a typed {@code artifact/*} port says both.
	 * </p>
	 */
	@PortDoc(label = "Artifacts",
		description = "Every produced file to upload - thumbnails, depth maps, generated images, speech audio")
	public static final InputPort<String> IN_ARTIFACTS = InputPort.many("artifacts", ContentTypeRegistry.ARTIFACT_ANY, String.class);

	@PortDoc(label = "Upload Report",
		description = "Per artifact: the object key, its size and whether it was uploaded or skipped as unchanged")
	public static final OutputPort<String> OUT_RESULT = OutputPort.one("result", ContentTypeRegistry.STRUCT_JSON, String.class);

	@PortDoc(label = "Uploaded Count", description = "How many objects ended up in the bucket")
	public static final OutputPort<Long> OUT_COUNT = OutputPort.one("count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	@PortDoc(label = "Flag", description = "Processing marker recording how this node finished for the item")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	private final S3Support s3;

	/**
	 * Graph-local id. Also the component {@code variant} and the ledger {@code node_id}, which is
	 * what lets two sinks writing to different buckets coexist on one asset.
	 */
	private String nodeId = KIND;

	private S3KeyTemplate template;

	@Inject
	public S3SinkNode(@Nullable LoomClient client, CortexOptions cortexOptions, S3SinkNodeOptions options, S3Support s3) {
		super(client, cortexOptions, options);
		this.s3 = s3;
		this.template = S3KeyTemplate.parse(options.getKeyTemplate());
	}

	@Override
	public String name() {
		return KIND;
	}

	@Override
	protected String nodeId() {
		return nodeId;
	}

	@Override
	public void configure(JsonObject nodeDef) {
		S3SinkNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("bucket")) {
			options.setBucket(nodeDef.getString("bucket"));
		}
		if (nodeDef.containsKey("keyTemplate")) {
			options.setKeyTemplate(nodeDef.getString("keyTemplate"));
		}
		if (nodeDef.containsKey("includeSource")) {
			options.setIncludeSource(nodeDef.getBoolean("includeSource"));
		}
		if (nodeDef.containsKey("createAssets")) {
			options.setCreateAssets(nodeDef.getBoolean("createAssets"));
		}
		if (nodeDef.containsKey("overwrite")) {
			options.setOverwrite(OverwritePolicy.parse(nodeDef.getString("overwrite")));
		}
		if (nodeDef.containsKey("deleteAfterUpload")) {
			options.setDeleteAfterUpload(nodeDef.getBoolean("deleteAfterUpload"));
		}
		if (nodeDef.containsKey("maxArtifacts")) {
			options.setMaxArtifacts(nodeDef.getInteger("maxArtifacts"));
		}
		if (nodeDef.containsKey("maxArtifactBytes")) {
			options.setMaxArtifactBytes(nodeDef.getLong("maxArtifactBytes"));
		}
		if (nodeDef.containsKey("failOnPartial")) {
			options.setFailOnPartial(nodeDef.getBoolean("failOnPartial"));
		}

		var validation = options.validate();
		if (validation.isInvalid()) {
			throw new IllegalStateException(String.join("; ", validation.getErrors()));
		}
		// Required here rather than in validate(): the registrar validates the worker's options for
		// every node it builds, and the bucket belongs on the node definition.
		if (options.getBucket() == null || options.getBucket().isBlank()) {
			throw new IllegalStateException("s3-sink requires a 'bucket'");
		}
		// Parsed once per configured instance rather than per media item.
		this.template = S3KeyTemplate.parse(options.getKeyTemplate());
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		// Any media type: what matters is what upstream produced, not what the input is.
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		if (!s3.isActive()) {
			// Fail rather than skip. Skipping would be silent data loss on a green run.
			return ctx.failure("This worker has no S3 configuration. Set --s3-endpoint "
				+ "(CORTEX_S3_ENDPOINT) or --s3-access-key (CORTEX_S3_ACCESS_KEY), or restrict the "
				+ "'s3-sink' kind to S3-capable workers via CORTEX_NODE_WHITELIST.").abort();
		}

		S3SinkNodeOptions options = options();
		String bucket = options.getBucket();
		if (bucket == null || bucket.isBlank()) {
			return ctx.failure("s3-sink requires a 'bucket'").abort();
		}

		List<SinkArtifact> artifacts = new ArtifactSelector(cortexOption().getMetaPath())
			.select(ctx.inputs(IN_ARTIFACTS).stream().map(Element::value).toList(), ctx.media(),
				options.isIncludeSource());

		artifacts = dropAlreadyRemoteMedia(artifacts, ctx.media());

		if (artifacts.isEmpty()) {
			// Legitimate: a sink downstream of a depthmap that skipped a video must not redden
			// every video in the run.
			return ctx.skipped("no artifacts").next();
		}
		if (artifacts.size() > options.getMaxArtifacts()) {
			return ctx.failure("selected " + artifacts.size() + " artifacts, over the maxArtifacts limit of "
				+ options.getMaxArtifacts() + "; a producer emitting this many is usually a bug").abort();
		}

		List<UploadedArtifact> results = new ArrayList<>(artifacts.size());
		for (SinkArtifact artifact : artifacts) {
			results.add(publish(ctx, artifact, bucket, asset));
		}

		return report(ctx, asset, results, bucket);
	}

	/**
	 * Skip the media item when it is already an object - an {@code s3-source} run would otherwise
	 * re-upload bytes it fetched moments ago. A cross-bucket archive wants a server-side copy,
	 * which is separate work.
	 */
	private List<SinkArtifact> dropAlreadyRemoteMedia(List<SinkArtifact> artifacts, LoomMedia media) {
		if (media == null || !S3Uri.isS3(media.reference())) {
			return artifacts;
		}
		List<SinkArtifact> kept = new ArrayList<>(artifacts.size());
		for (SinkArtifact artifact : artifacts) {
			if (SinkArtifact.SOURCE_MEDIA.equals(artifact.sourceNode())) {
				log.debug("[{}] media {} is already an object - not re-uploading it", nodeId, media.reference());
				continue;
			}
			kept.add(artifact);
		}
		return kept;
	}

	/** Upload one artifact and, when enabled, create the Loom asset that makes it retrievable. */
	private UploadedArtifact publish(NodeContext<LoomMedia> ctx, SinkArtifact artifact, String bucket,
		AssetResponse sourceAsset) {

		if (!artifact.present()) {
			// The producing node said it wrote this file and it is not here. Almost always the
			// affinity problem, and the message has to say so - otherwise it reads as a lost file.
			return new UploadedArtifact(artifact, State.MISSING)
				.error("artifact " + artifact.file() + " from " + artifact.describe()
					+ " is not present on this worker; s3-sink must run on the same worker as its "
					+ "producer - pin both into one affinity group or one node whitelist");
		}

		UploadedArtifact result = new UploadedArtifact(artifact, State.FAILED);
		try {
			long size = Files.size(artifact.file());
			long limit = options().getMaxArtifactBytes();
			if (limit > 0 && size > limit) {
				return result.error("artifact " + artifact.describe() + " is " + size
					+ " bytes, over the maxArtifactBytes limit of " + limit);
			}

			SHA512 artifactHash = HashUtils.computeSHA512(artifact.file().toFile());
			String key = renderKey(ctx, artifact, artifactHash, sourceAsset);
			String contentType = S3ContentTypes.of(artifact.file());
			result.location(bucket, key);

			S3ObjectStore store = s3.store();
			S3ObjectRef existing = shouldCheck() ? store.head(bucket, key) : null;
			if (existing != null && skipExisting(existing, size)) {
				result.state(State.PRESENT).stored(existing.etag(), existing.size(), contentType);
				log.debug("[{}] {} already at s3://{}/{}", nodeId, artifact.describe(), bucket, key);
			} else {
				S3ObjectRef stored = store.upload(bucket, key, artifact.file(), contentType);
				result.state(State.UPLOADED).stored(stored.etag(), stored.size(), contentType);
				log.info("[{}] uploaded {} ({} bytes) to s3://{}/{}", nodeId, artifact.describe(), size, bucket, key);
			}

			if (options().isCreateAssets()) {
				result.asset(createArtifactAsset(artifact, artifactHash, size, contentType, result.uri(), sourceAsset));
			}
			maybeDelete(artifact, result);
		} catch (Exception e) {
			log.warn("[{}] failed to publish {}: {}", nodeId, artifact.describe(), e.getMessage());
			result.state(State.FAILED).error(e.getMessage() == null ? e.toString() : e.getMessage());
		}
		return result;
	}

	private boolean shouldCheck() {
		return options().getOverwrite() != OverwritePolicy.ALWAYS;
	}

	private boolean skipExisting(S3ObjectRef existing, long localSize) {
		return switch (options().getOverwrite()) {
			case ALWAYS -> false;
			case NEVER -> true;
			// Key + size, not a content comparison: an ETag is not a content hash, because a
			// multipart upload reports an md5-of-md5s. With a content-addressed key that is a
			// strong statement that the identical bytes are already there.
			case IF_DIFFERENT -> existing.size() == localSize;
		};
	}

	private String renderKey(NodeContext<LoomMedia> ctx, SinkArtifact artifact, SHA512 artifactHash,
		AssetResponse sourceAsset) {
		SHA512 sourceHash = safeSourceHash(ctx.media());
		return template.render(new S3KeyTemplate.Values(
			artifactHash.toString(),
			sourceHash == null ? null : sourceHash.toString(),
			nodeId,
			artifact.sourceNode(),
			artifact.sourceKey(),
			artifact.extension(),
			artifact.fileName(),
			artifact.baseName(),
			sourceAsset == null ? null : String.valueOf(sourceAsset.getUuid()),
			artifact.index(),
			artifact.multiValued()));
	}

	private SHA512 safeSourceHash(LoomMedia media) {
		try {
			return media == null ? null : media.getSHA512();
		} catch (RuntimeException e) {
			// Only matters when the template asks for it, and then render() fails with a message
			// naming the placeholder - which is far clearer than an NPE here.
			return null;
		}
	}

	/**
	 * Create the Loom asset for an uploaded artifact, or return the existing one.
	 *
	 * <p>The same bytes are the same asset: {@code asset.sha512sum} is {@code UNIQUE}, so a
	 * re-published artifact resolves to the row already there rather than conflicting.</p>
	 *
	 * @return the asset uuid, or null offline or on failure
	 */
	private String createArtifactAsset(SinkArtifact artifact, SHA512 hash, long size, String contentType,
		String uri, AssetResponse sourceAsset) {
		if (client() == null) {
			return null;
		}
		try {
			AssetResponse existing = loadAssetQuietly(hash);
			if (existing != null) {
				return String.valueOf(existing.getUuid());
			}
			AssetCreateRequest request = new AssetCreateRequest();
			request.setFile(new FileInfo()
				.setFilename(artifact.fileName())
				.setMimeType(contentType)
				// The asset.initial_origin column comment sanctions "first s3 path" as a value.
				.setOrigin(uri)
				.setSize(size)
				.setFirstSeen(Instant.now()));
			request.setHashes(new HashInfo().setSHA512(hash));
			request.setMeta(new JsonObject()
				.put("producedBy", KIND)
				.put("sinkNodeId", nodeId)
				.put("sourceNode", artifact.sourceNode())
				.put("sourceKey", artifact.sourceKey())
				.put("sourceAssetUuid", sourceAsset == null ? null : String.valueOf(sourceAsset.getUuid())));
			AssetResponse created = client().createAsset(request).sync().body();
			log.info("[{}] registered artifact asset {} for {}", nodeId, created.getUuid(), uri);
			return String.valueOf(created.getUuid());
		} catch (Exception e) {
			// The bytes are safe; failing to register them is worth reporting but the artifact is
			// not lost, so this degrades rather than throwing.
			log.warn("[{}] uploaded {} but could not create its asset: {}", nodeId, uri, e.getMessage());
			return null;
		}
	}

	private AssetResponse loadAssetQuietly(SHA512 hash) {
		try {
			return client().loadAsset(hash).sync().body();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Remove the local file, but only when it is safe to do so.
	 *
	 * <p>Never the media item, and never anything outside the meta path: an {@code artifacts} entry
	 * names an arbitrary upstream output and a script {@code PATH} output can be any string.</p>
	 */
	private void maybeDelete(SinkArtifact artifact, UploadedArtifact result) {
		if (!options().isDeleteAfterUpload() || !result.isStored()) {
			return;
		}
		if (SinkArtifact.SOURCE_MEDIA.equals(artifact.sourceNode())) {
			log.debug("[{}] refusing to delete the media item {}", nodeId, artifact.file());
			return;
		}
		Path metaPath = cortexOption().getMetaPath();
		if (metaPath == null || !artifact.file().startsWith(metaPath.toAbsolutePath().normalize())) {
			log.warn("[{}] refusing to delete {} - it is outside the meta path", nodeId, artifact.file());
			return;
		}
		try {
			result.deleted(Files.deleteIfExists(artifact.file()));
		} catch (IOException e) {
			// The bytes are already in the bucket, which was the point.
			log.warn("[{}] could not delete {} after upload: {}", nodeId, artifact.file(), e.getMessage());
		}
	}

	/** Emit outputs, persist the component and ledger, and decide the node's state. */
	private NodeResult report(NodeContext<LoomMedia> ctx, AssetResponse asset, List<UploadedArtifact> results,
		String bucket) {

		int stored = (int) results.stream().filter(UploadedArtifact::isStored).count();
		List<UploadedArtifact> failed = results.stream().filter(r -> !r.isStored()).toList();

		JsonArray entries = new JsonArray();
		results.forEach(r -> entries.add(r.toJson()));
		JsonObject payload = new JsonObject()
			.put("bucket", bucket)
			.put("nodeId", nodeId)
			.put("count", results.size())
			.put("uploaded", stored)
			.put("artifacts", entries);

		String flag = failed.isEmpty() ? "DONE" : (stored == 0 ? "FAILED" : "PARTIAL");
		ctx.output(OUT_FLAG, flag);
		ctx.output(OUT_COUNT, (long) stored);
		ctx.output(OUT_RESULT, payload.encode());

		String reason = failed.isEmpty() ? null
			: "uploaded " + stored + " of " + results.size() + " artifacts; first failure: "
				+ failed.get(0).artifact().describe() + ": " + failed.get(0).toJson().getString("error");

		persist(ctx, asset, payload, bucket, failed.isEmpty() ? ResultState.SUCCESS : ResultState.FAILED, reason);

		ctx.print(flag, stored + "/" + results.size() + " -> s3://" + bucket);

		if (!failed.isEmpty() && options().isFailOnPartial()) {
			// abort(), never next(): NodeContextImpl.next() ignores a recorded failure cause and
			// reports SUCCESS. For a sink, "green node, nothing in the bucket" is the worst
			// possible outcome.
			return ctx.failure(reason).abort();
		}
		return ctx.next();
	}

	private void persist(NodeContext<LoomMedia> ctx, AssetResponse asset, JsonObject payload, String bucket,
		ResultState state, String reason) {
		if (asset == null || client() == null) {
			return;
		}
		try {
			JsonCompCreateRequest request = new JsonCompCreateRequest();
			request.setNodeKind(name());
			request.setSchemaType(SCHEMA_TYPE);
			// The node id discriminates two sinks on one asset, exactly as ScriptNode does.
			request.setVariant(nodeId);
			request.setProducerVersion(bucket);
			request.setData(payload);
			UUID compUuid = client().createAssetJsonComp(asset.getUuid(), request).sync().body().getUuid();
			recordNodeResult(asset, ctx, state, reason, bucket, resultRef("asset_json_comp", compUuid));
		} catch (Exception e) {
			log.warn("[{}] failed to persist the artifact index for asset {}: {}", nodeId, asset.getUuid(),
				e.getMessage());
			recordNodeResult(asset, ctx, ResultState.FAILED, e.getMessage(), bucket, null);
		}
	}
}
