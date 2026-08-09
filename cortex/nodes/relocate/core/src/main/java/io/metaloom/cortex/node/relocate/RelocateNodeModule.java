package io.metaloom.cortex.node.relocate;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractNodeModule;
import io.metaloom.cortex.common.option.CortexNodeOptionDeserializerInfo;

/**
 * Wiring for the {@code move} and {@code assign} kinds.
 *
 * <p>
 * Two kinds in one module because they share the Loom target resolution and are two halves of one idea - where an asset lives, physically and
 * organizationally. {@code cortex/nodes/hash} (four kinds) and {@code cortex/nodes/dedup} (three) are the precedent.
 * </p>
 *
 * <p>
 * ⚠️ Neither node is {@code @Singleton}. Both are {@code PipelineConfigurable}, so {@code RegistryNodeRegistrar} mutates them per task through the
 * {@code Provider}; a shared instance would let one move node's destination leak into another's.
 * </p>
 */
@Module
public abstract class RelocateNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindMoveNode(MoveNode node);

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindAssignNode(AssignNode node);

	/** The {@code @IntoMap @StringKey} bindings are what make the kinds schedulable at all. */
	@Binds
	@IntoMap
	@StringKey(MoveNode.KIND)
	abstract FilesystemNode<?, ?> kindMove(MoveNode node);

	@Binds
	@IntoMap
	@StringKey(AssignNode.KIND)
	abstract FilesystemNode<?, ?> kindAssign(AssignNode node);

	/**
	 * One binding per {@link MoveTarget}. Adding a destination is a strategy class and a line here - never an edit to {@link MoveNode}.
	 */
	@Binds
	@IntoMap
	@MoveTargetKey(MoveTarget.FOLDER)
	abstract MoveDestination bindFolderDestination(FolderDestination destination);

	@Binds
	@IntoMap
	@MoveTargetKey(MoveTarget.POOL)
	abstract MoveDestination bindPoolDestination(PoolDestination destination);

	@Binds
	@IntoMap
	@MoveTargetKey(MoveTarget.LIBRARY)
	abstract MoveDestination bindLibraryDestination(LibraryDestination destination);

	@Binds
	@IntoMap
	@MoveTargetKey(MoveTarget.S3_BUCKET)
	abstract MoveDestination bindS3BucketDestination(S3BucketDestination destination);

	@Binds
	@IntoMap
	@AssignTargetKey(AssignTarget.COLLECTION)
	abstract AssignDestination bindCollectionAssignment(CollectionAssignment destination);

	@Binds
	@IntoMap
	@AssignTargetKey(AssignTarget.LIBRARY)
	abstract AssignDestination bindLibraryAssignment(LibraryAssignment destination);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo moveOptionInfo() {
		return new CortexNodeOptionDeserializerInfo(MoveNodeOptions.class, MoveNodeOptions.KEY);
	}

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo assignOptionInfo() {
		return new CortexNodeOptionDeserializerInfo(AssignNodeOptions.class, AssignNodeOptions.KEY);
	}

	@Provides
	public static MoveNodeOptions moveOptions(CortexOptions options) {
		return nodeOptions(options, MoveNodeOptions.KEY, new MoveNodeOptions());
	}

	@Provides
	public static AssignNodeOptions assignOptions(CortexOptions options) {
		return nodeOptions(options, AssignNodeOptions.KEY, new AssignNodeOptions());
	}
}
