package io.metaloom.cortex.node.script;

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
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.js.GraalJsScriptEngine;

/**
 * Wiring for the {@code script} node and its engines.
 *
 * <p>
 * ⚠️ {@link ScriptNode} is deliberately <strong>not</strong> {@code @Singleton}: it is mutated by
 * {@code configure(...)} per task, so two concurrent script nodes must not share an instance. The
 * kind map holds a {@code Provider}, which already yields a fresh node per task -
 * {@code ScriptNodeSingletonTest} pins that.
 * </p>
 *
 * <p>
 * Engines use the same {@code @IntoMap @StringKey} shape as node kinds, so adding a language is a
 * one-line binding in its own module and never an edit to {@code ScriptNode}.
 * </p>
 */
@Module
public abstract class ScriptNodeModule extends AbstractNodeModule {

	@Binds
	@IntoSet
	abstract FilesystemNode<?, ?> bindNode(ScriptNode node);

	/** Without this map binding the node exists but is never schedulable. */
	@Binds
	@IntoMap
	@StringKey(ScriptNode.KIND)
	abstract FilesystemNode<?, ?> kindScript(ScriptNode node);

	@Binds
	@IntoMap
	@StringKey(GraalJsScriptEngine.ID)
	abstract ScriptEngine bindJsEngine(GraalJsScriptEngine engine);

	@IntoSet
	@Provides
	public static CortexNodeOptionDeserializerInfo optionInfo() {
		return new CortexNodeOptionDeserializerInfo(ScriptNodeOptions.class, ScriptNodeOptions.KEY);
	}

	/**
	 * Worker-level defaults. The real configuration arrives per node instance from the pipeline
	 * definition; this block exists so an operator can set fleet-wide limits (a shorter timeout, a
	 * smaller output cap) that a definition then layers over.
	 */
	@Provides
	public static ScriptNodeOptions options(CortexOptions options) {
		return nodeOptions(options, ScriptNodeOptions.KEY, new ScriptNodeOptions());
	}
}
