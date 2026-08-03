package io.metaloom.cortex.api.node.spec;

import java.util.Collection;

/**
 * Contributes node classes to the harvest — the extension point a third-party node uses to reach the
 * pipeline editor.
 *
 * <p>
 * Discovered with {@code ServiceLoader}, so a jar dropped onto a worker's classpath announces its
 * nodes with no change to Loom, to Cortex, or to anything else already deployed. That is the whole
 * point: a custom node used to be runnable but unauthorable, because its contract lived in Loom's jar.
 * </p>
 *
 * <p>
 * <strong>Return class literals, not instances.</strong> A {@code Class} literal does not run the
 * class's static initializer, so listing a node here costs nothing — which matters, because some nodes
 * load native libraries in a static block. Only harvesting a node reads its port constants, and that
 * happens for registered nodes only.
 * </p>
 *
 * <pre>{@code
 * public class AcmeNodeSpecSource implements NodeSpecSource {
 *     public Collection<Class<?>> nodeClasses() {
 *         return List.of(AcmeNsfwNode.class);
 *     }
 * }
 * }</pre>
 *
 * <p>
 * Register it in {@code META-INF/services/io.metaloom.cortex.api.node.spec.NodeSpecSource}.
 * </p>
 */
public interface NodeSpecSource {

	/**
	 * Node classes this source contributes. Each should carry {@link NodeSpec}; one that does not is
	 * skipped with a warning rather than failing the worker's whole announcement.
	 */
	Collection<Class<?>> nodeClasses();
}
