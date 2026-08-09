package io.metaloom.cortex.node.relocate;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;

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
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.vertx.core.json.JsonObject;

/**
 * Adds an asset to a collection or a library.
 *
 * <p>
 * 🔴 <b>This node never touches a file.</b> Membership is a row, not a relocation: a collection has no path, and library membership is independent of
 * where the bytes physically sit. Every one of its tests asserts the source file is byte-identical and at the same path afterwards, because the day
 * that stops being true is the day "add to the published set" starts moving people's originals.
 * </p>
 *
 * <p>
 * Relocating bytes is {@link MoveNode}. The two are separate kinds so that neither can quietly become the other.
 * </p>
 */
@NodeSpec(nodeId = AssignNode.KIND, name = "Assign", icon = "playlist_add", category = NodeCategory.OUTPUT,
	description = "Add the asset to a collection or a library. Writes the membership only - it never moves, copies or deletes a file.",
	parameters = {
		@ParamOverride(key = "processIncomplete", hidden = true),
		@ParamOverride(key = "retryFailed", hidden = true)
	})
public class AssignNode extends AbstractMediaNode<AssignNodeOptions> implements PipelineConfigurable {

	public static final String KIND = "assign";

	@PortDoc(label = "Media", description = "The item to add to the target")
	public static final InputPort<LoomMedia> IN_MEDIA = InputPort.one("media", ContentTypeRegistry.MEDIA_ANY, LoomMedia.class);

	@PortDoc(label = "Assigned", description = "Whether a new membership was written, so a downstream node can branch on it")
	public static final OutputPort<Boolean> OUT_ASSIGNED = OutputPort.one("assigned", ContentTypeRegistry.SCALAR_BOOLEAN, Boolean.class);

	@PortDoc(label = "Target", description = "Uuid of the collection or library the asset now belongs to")
	public static final OutputPort<String> OUT_TARGET = OutputPort.one("target", ContentTypeRegistry.SCALAR_STRING, String.class);

	private final Map<AssignTarget, Provider<AssignDestination>> destinations;

	private String nodeId = KIND;

	@Inject
	public AssignNode(@Nullable LoomClient client, CortexOptions cortexOption, AssignNodeOptions options,
		Map<AssignTarget, Provider<AssignDestination>> destinations) {
		super(client, cortexOption, options);
		this.destinations = destinations;
	}

	@Override
	public String name() {
		return KIND;
	}

	@Override
	protected String nodeId() {
		return KIND + ":" + nodeId;
	}

	@Override
	public void configure(JsonObject nodeDef) {
		AssignNodeOptions options = options();
		nodeId = nodeDef.getString("id", KIND);

		if (nodeDef.containsKey("target")) {
			String raw = nodeDef.getString("target");
			try {
				options.setTarget(AssignTarget.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException | NullPointerException e) {
				throw new IllegalStateException("Assign node '" + nodeId + "' has unknown target '" + raw + "'; expected one of "
					+ java.util.Arrays.toString(AssignTarget.values()));
			}
		}
		if (nodeDef.containsKey("collectionUuid")) {
			options.setCollectionUuid(nodeDef.getString("collectionUuid"));
		}
		if (nodeDef.containsKey("collectionName")) {
			options.setCollectionName(nodeDef.getString("collectionName"));
		}
		if (nodeDef.containsKey("libraryUuid")) {
			options.setLibraryUuid(nodeDef.getString("libraryUuid"));
		}
		if (nodeDef.containsKey("onMissing")) {
			options.setOnMissing(nodeDef.getString("onMissing"));
		}
		if (nodeDef.containsKey("dryRun")) {
			options.setDryRun(nodeDef.getBoolean("dryRun"));
		}

		var validation = options.validate();
		if (validation.isInvalid()) {
			throw new IllegalStateException(String.join("; ", validation.getErrors()));
		}

		java.util.List<String> problems = destination(options.getTarget()).validate(options);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Assign node '" + nodeId + "': " + String.join("; ", problems));
		}
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		return true;
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws Exception {
		AssignNodeOptions options = options();
		AssignDestination destination = destination(options.getTarget());

		// A logical membership cannot exist without the asset row, so an unknown or unreachable asset is
		// nothing to do rather than something to fail over.
		if (client() == null || asset == null) {
			return skip(ctx, asset, "the asset is not known to Loom");
		}

		UUID targetUuid;
		try {
			targetUuid = destination.resolve(client(), options);
		} catch (Exception e) {
			return fail(ctx, asset, e.getMessage());
		}

		if (targetUuid == null) {
			String what = destination.describe(options);
			OnMissing onMissing = OnMissing.parse(options.getOnMissing());
			if (onMissing == OnMissing.SKIP) {
				return skip(ctx, asset, what + " does not exist");
			}
			// CREATE reaching here means the creation itself was refused - almost always a worker token
			// without CREATE_COLLECTION. Failing names the cause; skipping would blame the data.
			return fail(ctx, asset, what + " does not exist"
				+ (onMissing == OnMissing.CREATE ? " and could not be created; does the worker's token hold the create permission?" : ""));
		}

		try {
			if (destination.isMember(client(), targetUuid, asset.getUuid())) {
				ctx.output(OUT_ASSIGNED, false);
				ctx.output(OUT_TARGET, targetUuid.toString());
				recordNodeResult(asset, ctx, ResultState.SKIPPED, "already in " + destination.describe(options), producerVersion(), null);
				return ctx.skipped("already in " + destination.describe(options)).next();
			}

			if (isDryrun() || options.isDryRun()) {
				ctx.output(OUT_ASSIGNED, false);
				ctx.output(OUT_TARGET, targetUuid.toString());
				return ctx.skipped("dry run: would assign to " + destination.describe(options)).next();
			}

			destination.link(client(), targetUuid, asset.getUuid());
		} catch (Exception e) {
			return fail(ctx, asset, e.getMessage());
		}

		ctx.output(OUT_ASSIGNED, true);
		ctx.output(OUT_TARGET, targetUuid.toString());
		recordNodeResult(asset, ctx, ResultState.SUCCESS, "assigned to " + destination.describe(options), producerVersion(),
			resultRef(destination.table(), targetUuid));
		return ctx.origin(COMPUTED).next();
	}

	private NodeResult skip(NodeContext<LoomMedia> ctx, AssetResponse asset, String reason) {
		ctx.output(OUT_ASSIGNED, false);
		recordNodeResult(asset, ctx, ResultState.SKIPPED, reason, producerVersion(), null);
		return ctx.skipped(reason).next();
	}

	/** 🔴 abort(), never next(): next() drops the cause and reports SUCCESS. */
	private NodeResult fail(NodeContext<LoomMedia> ctx, AssetResponse asset, String reason) {
		ctx.output(OUT_ASSIGNED, false);
		recordNodeResult(asset, ctx, ResultState.FAILED, reason, producerVersion(), null);
		return ctx.failure(reason).abort();
	}

	private AssignDestination destination(AssignTarget target) {
		Provider<AssignDestination> provider = destinations.get(target);
		if (provider == null) {
			throw new IllegalStateException("No destination is bound for the " + target + " target");
		}
		return provider.get();
	}

	private String producerVersion() {
		return KIND + "/1:" + options().getTarget().name().toLowerCase(Locale.ROOT);
	}
}
