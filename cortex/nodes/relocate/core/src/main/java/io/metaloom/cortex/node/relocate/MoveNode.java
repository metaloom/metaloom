package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;

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
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.cortex.fs.ConflictPolicy;
import io.metaloom.cortex.fs.CrossDevicePolicy;
import io.metaloom.cortex.fs.LocalMover;
import io.metaloom.cortex.fs.MoveOutcome;
import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.cortex.s3.S3Uri;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Relocates an asset's bytes to a folder, a storage pool, a library's pool or an S3 bucket.
 *
 * <p>
 * Two nodes moved files before this one, both hard-wired to a duplicates folder and usable only for duplicates. Trash disposal, safety quarantine and
 * cold-tier staging all need the same primitive, so this is that primitive: one kind, a {@link MoveTarget} seam for the destination, and a single
 * place where a source file is ever unlinked.
 * </p>
 *
 * <p>
 * <b>What it will not do.</b> It never deletes without first proving the destination holds the same bytes, and even then only when explicitly asked
 * ({@link SourcePolicy#DELETE_AFTER_VERIFY}); it never overwrites a destination, because a name collision in a trash folder is very often a different
 * asset; and it never copies across a filesystem boundary unless told to, because that turns a rename into an unbounded byte copy.
 * </p>
 *
 * <p>
 * Membership is not relocation: putting an asset into a collection moves no bytes and belongs to the {@code assign} node.
 * </p>
 */
@NodeSpec(nodeId = MoveNode.KIND, name = "Move", icon = "drive_file_move", category = NodeCategory.OUTPUT,
	description = "Relocate an asset's file to a folder, a storage pool, a library or an S3 bucket. Never overwrites, and only removes the original once the destination is verified.",
	defaultMode = io.metaloom.loom.nodes.spec.NodeMode.SEQUENTIAL,
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true)
	})
public class MoveNode extends AbstractMediaNode<MoveNodeOptions> implements PipelineConfigurable {

	private static final Logger log = LoggerFactory.getLogger(MoveNode.class);

	public static final String KIND = "move";

	/**
	 * The item to relocate.
	 *
	 * <p>
	 * ⚠️ Declared for the descriptor and read with {@code ctx.media()}. {@code ValueCoercer} turns every media value into a String, so
	 * {@code ctx.input(IN_MEDIA)} on a {@code media/*} port always throws.
	 * </p>
	 */
	@PortDoc(label = "Media", description = "The item whose bytes should be relocated")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	@PortDoc(label = "Moved", description = "Whether the bytes actually went anywhere, so a downstream node can branch on it")
	public static final OutputPort<Boolean> OUT_MOVED = OutputPort.one("moved", ContentTypeRegistry.SCALAR_BOOLEAN, Boolean.class);

	@PortDoc(label = "Location", description = "Where the bytes are now: an absolute path, or an s3:// reference")
	public static final OutputPort<String> OUT_PATH = OutputPort.one("path", ContentTypeRegistry.SCALAR_STRING, String.class);

	@PortDoc(label = "Flag", description = "How this node finished for the item: MOVED, COPIED, ALREADY_THERE, SKIPPED or DRY_RUN")
	public static final OutputPort<String> OUT_FLAG = OutputPort.one("flag", ContentTypeRegistry.SCALAR_STRING, String.class);

	private final Map<MoveTarget, Provider<MoveDestination>> destinations;

	private final LoomLocationWriter locationWriter;

	private final S3Support s3;

	private final LocalMover mover;

	private String nodeId = KIND;

	@Inject
	public MoveNode(@Nullable LoomClient client, CortexOptions cortexOption, MoveNodeOptions options,
		Map<MoveTarget, Provider<MoveDestination>> destinations, LoomLocationWriter locationWriter, S3Support s3) {
		this(client, cortexOption, options, destinations, locationWriter, s3, new LocalMover());
	}

	/**
	 * @param mover
	 *            injectable so a test can stub the filesystem-boundary probe and exercise the cross-device paths without a second mount point
	 */
	public MoveNode(@Nullable LoomClient client, CortexOptions cortexOption, MoveNodeOptions options,
		Map<MoveTarget, Provider<MoveDestination>> destinations, LoomLocationWriter locationWriter, S3Support s3, LocalMover mover) {
		super(client, cortexOption, options);
		this.destinations = destinations;
		this.locationWriter = locationWriter;
		this.s3 = s3;
		this.mover = mover;
	}

	@Override
	public String name() {
		return KIND;
	}

	/**
	 * Graph-local, because {@code asset_node_result} is unique on {@code (asset_uuid, node_kind, node_id)} and two move nodes in one pipeline - one to
	 * trash, one to an archive bucket - are an obvious thing to build.
	 */
	@Override
	protected String nodeId() {
		return KIND + ":" + nodeId;
	}

	@Override
	public void configure(JsonObject nodeDef) {
		MoveNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("target")) {
			String raw = nodeDef.getString("target");
			try {
				options.setTarget(MoveTarget.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalStateException("Move node '" + nodeId + "' has unknown target '" + raw + "'; expected one of "
					+ java.util.Arrays.toString(MoveTarget.values()));
			}
		}
		if (nodeDef.containsKey("targetFolder")) {
			options.setTargetFolder(Paths.get(nodeDef.getString("targetFolder")));
		}
		if (nodeDef.containsKey("sourceRoot")) {
			options.setSourceRoot(Paths.get(nodeDef.getString("sourceRoot")));
		}
		if (nodeDef.containsKey("layout")) {
			options.setLayout(Layout.parse(nodeDef.getString("layout")));
		}
		if (nodeDef.containsKey("poolUuid")) {
			options.setPoolUuid(nodeDef.getString("poolUuid"));
		}
		if (nodeDef.containsKey("libraryUuid")) {
			options.setLibraryUuid(nodeDef.getString("libraryUuid"));
		}
		if (nodeDef.containsKey("bucket")) {
			options.setBucket(nodeDef.getString("bucket"));
		}
		if (nodeDef.containsKey("onConflict")) {
			options.setOnConflict(nodeDef.getString("onConflict"));
		}
		if (nodeDef.containsKey("crossDevice")) {
			options.setCrossDevice(nodeDef.getString("crossDevice"));
		}
		if (nodeDef.containsKey("sourcePolicy")) {
			options.setSourcePolicy(nodeDef.getString("sourcePolicy"));
		}
		if (nodeDef.containsKey("verify")) {
			options.setVerify(nodeDef.getString("verify"));
		}
		if (nodeDef.containsKey("updateLocation")) {
			options.setUpdateLocation(nodeDef.getBoolean("updateLocation"));
		}
		if (nodeDef.containsKey("dryRun")) {
			options.setDryRun(nodeDef.getBoolean("dryRun"));
		}

		var validation = options.validate();
		if (validation.isInvalid()) {
			throw new IllegalStateException(String.join("; ", validation.getErrors()));
		}

		// Per-target requirements belong here rather than in validate(): the registrar validates the
		// worker's options for every node it builds, and "which pool" is a property of this node instance.
		MoveDestination destination = destination(options.getTarget());
		java.util.List<String> problems = destination.validate(options);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Move node '" + nodeId + "': " + String.join("; ", problems));
		}
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		LoomMedia media = ctx.media();
		MoveNodeOptions options = options();

		// A materialized remote item's path points at a local download cache, not at the asset. Moving that
		// would relocate a cache entry and tell Loom the asset had moved with it.
		String reference = media.reference();
		if (reference != null && !reference.equals(media.absolutePath())) {
			return skip(ctx, asset, "media is not a local file: " + reference);
		}

		MovePlan plan;
		try {
			plan = destination(options.getTarget()).plan(client(), media, options);
		} catch (Exception e) {
			return fail(ctx, asset, e.getMessage());
		}

		if (plan.alreadyThere()) {
			ctx.output(OUT_MOVED, false);
			ctx.output(OUT_PATH, plan.locator());
			ctx.output(OUT_FLAG, "ALREADY_THERE");
			recordNodeResult(asset, ctx, ResultState.SKIPPED, "already at " + plan.locator(), producerVersion(), null);
			return ctx.skipped("already at " + plan.locator()).next();
		}

		if (isDryrun() || options.isDryRun()) {
			ctx.output(OUT_MOVED, false);
			ctx.output(OUT_PATH, plan.locator());
			ctx.output(OUT_FLAG, "DRY_RUN");
			return ctx.skipped("dry run: would move to " + plan.locator()).next();
		}

		String previousLocator = media.absolutePath();
		try {
			return plan.kind() == MovePlan.Kind.S3
				? moveToBucket(ctx, asset, media, plan, previousLocator)
				: moveOnFilesystem(ctx, asset, media, plan, previousLocator, options);
		} catch (Exception e) {
			return fail(ctx, asset, e.getMessage());
		}
	}

	private NodeResult moveOnFilesystem(NodeContext<LoomMedia> ctx, AssetResponse asset, LoomMedia media, MovePlan plan, String previousLocator,
		MoveNodeOptions options) throws IOException {

		SourcePolicy sourcePolicy = SourcePolicy.parse(options.getSourcePolicy());
		MoveOutcome outcome = mover.move(
			media.file().toPath(),
			plan.targetPath(),
			ConflictPolicy.parse(options.getOnConflict()),
			CrossDevicePolicy.parse(options.getCrossDevice()),
			sourcePolicy == SourcePolicy.DELETE_AFTER_VERIFY,
			verifier(options));

		if (outcome.isSkipped()) {
			return skip(ctx, asset, outcome.reason());
		}

		// The plan named a preferred path; a conflict may have landed the bytes next to it.
		MovePlan actual = MovePlan.filesystem(outcome.target(), plan.libraryUuid(), plan.poolUuid());
		return finish(ctx, asset, media, actual, previousLocator, outcome.state() == MoveOutcome.State.MOVED, options);
	}

	private NodeResult moveToBucket(NodeContext<LoomMedia> ctx, AssetResponse asset, LoomMedia media, MovePlan plan, String previousLocator)
		throws IOException {

		MoveNodeOptions options = options();
		Path source = media.file().toPath();
		String contentType = io.metaloom.cortex.s3.S3ContentTypes.of(source);

		S3ObjectRef stored = s3.store().upload(plan.bucket(), plan.key(), source, contentType);

		// Verify before considering the original expendable. head() rather than a download: the key is
		// derived from the content hash, so an object of the right size at that key is the right object.
		S3ObjectRef check = s3.store().head(plan.bucket(), plan.key());
		long localSize = Files.size(source);
		if (check == null || check.size() != localSize) {
			throw new IOException("The upload of " + source + " to " + plan.locator() + " did not verify (expected " + localSize + " bytes, found "
				+ (check == null ? "nothing" : check.size() + " bytes") + "); the local file was kept");
		}
		log.debug("Uploaded {} to {} ({} bytes, etag {})", source, plan.locator(), stored.size(), stored.etag());

		boolean removed = false;
		if (SourcePolicy.parse(options.getSourcePolicy()) == SourcePolicy.DELETE_AFTER_VERIFY) {
			Files.delete(source);
			removed = true;
		}
		return finish(ctx, asset, media, plan, previousLocator, removed, options);
	}

	/**
	 * Emit the ports, tell Loom, and write the ledger row.
	 */
	private NodeResult finish(NodeContext<LoomMedia> ctx, AssetResponse asset, LoomMedia media, MovePlan plan, String previousLocator,
		boolean sourceRemoved, MoveNodeOptions options) {

		String flag = sourceRemoved ? "MOVED" : "COPIED";
		ctx.output(OUT_MOVED, true);
		ctx.output(OUT_PATH, plan.locator());
		ctx.output(OUT_FLAG, flag);

		// Only when the original is gone does the media handle now describe the new place. After a copy the
		// item is still where the rest of the graph expects it, and re-pointing it would make every later
		// node read the archive rather than the working file.
		if (sourceRemoved && plan.kind() == MovePlan.Kind.FILESYSTEM) {
			media.setPath(plan.targetPath());
		}

		UUID binaryUuid = null;
		if (options.isUpdateLocation()) {
			binaryUuid = locationWriter.record(client(), asset, plan, previousLocator);
		}

		recordNodeResult(asset, ctx, ResultState.SUCCESS, flag.toLowerCase(Locale.ROOT) + " to " + plan.locator(), producerVersion(),
			binaryUuid == null ? null : resultRef("asset_location", binaryUuid));
		return ctx.origin(COMPUTED).next();
	}

	private LocalMover.Verifier verifier(MoveNodeOptions options) {
		if (VerifyPolicy.parse(options.getVerify()) == VerifyPolicy.SIZE) {
			return LocalMover.SIZE_VERIFIER;
		}
		return (source, target) -> {
			SHA512 sourceHash = HashUtils.computeSHA512(source.toFile());
			SHA512 targetHash = HashUtils.computeSHA512(target.toFile());
			return sourceHash != null && sourceHash.equals(targetHash);
		};
	}

	private NodeResult skip(NodeContext<LoomMedia> ctx, AssetResponse asset, String reason) {
		ctx.output(OUT_MOVED, false);
		ctx.output(OUT_FLAG, "SKIPPED");
		recordNodeResult(asset, ctx, ResultState.SKIPPED, reason, producerVersion(), null);
		return ctx.skipped(reason).next();
	}

	/**
	 * 🔴 Always {@code abort()}. {@code NodeContextImpl.next()} reads only the skip reason and reports SUCCESS, so a failure returned through it is a
	 * green run that moved nothing - the single worst bug a file mover can have, and the one both dedup nodes shipped with.
	 */
	private NodeResult fail(NodeContext<LoomMedia> ctx, AssetResponse asset, String reason) {
		ctx.output(OUT_MOVED, false);
		ctx.output(OUT_FLAG, "FAILED");
		recordNodeResult(asset, ctx, ResultState.FAILED, reason, producerVersion(), null);
		return ctx.failure(reason).abort();
	}

	private MoveDestination destination(MoveTarget target) {
		Provider<MoveDestination> provider = destinations.get(target);
		if (provider == null) {
			throw new IllegalStateException("No destination is bound for the " + target + " target");
		}
		return provider.get();
	}

	/**
	 * Changes whenever the meaning of the output changes: the target decides where bytes land, and the source policy decides whether the original
	 * survives, so a ledger row records both.
	 */
	private String producerVersion() {
		MoveNodeOptions options = options();
		return KIND + "/1:" + options.getTarget().name().toLowerCase(Locale.ROOT) + ":" + options.getSourcePolicy().toLowerCase(Locale.ROOT);
	}

	/** Exposed for the tests that assert an s3:// reference is left alone. */
	static boolean isRemote(String reference) {
		return reference != null && S3Uri.isS3(reference);
	}
}
