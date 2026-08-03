package io.metaloom.cortex.node.hello;

import java.util.Collection;
import java.util.List;

import io.metaloom.cortex.api.node.spec.NodeSpecSource;

/**
 * Contributes this jar's nodes to the worker's contract harvest.
 *
 * <p>
 * This is the whole integration point for a third-party node. Cortex knows about its own built-in
 * nodes by name; it finds yours through {@code ServiceLoader}, which is why this class is listed in
 * {@code src/main/resources/META-INF/services/io.metaloom.cortex.api.node.spec.NodeSpecSource}.
 * Without it, {@link HelloWorldNode} would still <em>run</em> — it is registered with the node
 * factory in {@code PipelineNodeFactoryModule} — but it would never appear in the pipeline editor,
 * which is precisely the "runnable but unauthorable" gap this mechanism closes.
 * </p>
 *
 * <p>
 * Return class literals, never instances. A {@code Class} literal does not run the class's static
 * initializer, so listing a node here costs nothing even for nodes that load native libraries; only
 * the harvest itself touches them, and only for nodes this worker is actually registered to run.
 * </p>
 */
public class HelloWorldNodeSpecSource implements NodeSpecSource {

	@Override
	public Collection<Class<?>> nodeClasses() {
		return List.of(HelloWorldNode.class);
	}
}
