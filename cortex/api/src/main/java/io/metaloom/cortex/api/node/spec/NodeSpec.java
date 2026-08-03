package io.metaloom.cortex.api.node.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeMode;

/**
 * Declares the authored half of a node's contract, on the node class itself.
 *
 * <p>
 * A node already states most of its contract in Java: its {@code InputPort}/{@code OutputPort}
 * constants carry every port id, content type, cardinality and value type, and its options class
 * carries every parameter key, type and default. {@link NodeSpecHarvester} reads all of that
 * reflectively — from the <em>same</em> declarations the node executes against, so it cannot drift.
 * </p>
 *
 * <p>
 * What reflection cannot produce is prose and intent: a display name, a description, an icon, a
 * palette category. That is what this annotation carries, and nothing more. Resist the urge to
 * restate a port list here — a second source of truth for the derivable 80% is precisely the problem
 * the hand-written {@code NodeDescriptorProvider}s had.
 * </p>
 *
 * <pre>{@code
 * @NodeSpec(nodeId = "sentiment", name = "Sentiment Analysis", icon = "mood", category = NodeCategory.ANALYSIS,
 *     description = "Score the polarity of text produced by an upstream node. German and English.")
 * public class SentimentNode extends AbstractMediaNode<SentimentNodeOptions> {
 *     @PortDoc(label = "Text", description = "The prose to score")
 *     public static final InputPort<String> IN_TEXT = InputPort.one("text", TEXT_ANY, String.class);
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NodeSpec {

	/**
	 * The node <strong>type</strong> id — {@code ocr}, {@code whisper}, {@code sentiment}. The same
	 * string the Dagger {@code @StringKey} binding uses and a pipeline definition's {@code type}
	 * selects.
	 *
	 * <p>
	 * Not to be confused with the graph-instance id (also called {@code nodeId} on {@code NodeTask})
	 * or the worker id (also called {@code nodeId} on {@code ProcessorRegistration}). Three things
	 * wear this name; this one is the type.
	 * </p>
	 */
	String nodeId();

	/** Human-readable display name for the palette. */
	String name();

	/** What the node does, shown in the palette and the node card. */
	String description() default "";

	/**
	 * Icon key for the editor.
	 *
	 * <p>
	 * The editor resolves this against a compile-time map of imported icon components and falls back
	 * to the category icon when the name is unknown — so a third-party node renders sensibly, but
	 * cannot introduce a new icon by naming one.
	 * </p>
	 */
	String icon() default "";

	/** Palette grouping. */
	NodeCategory category();

	/**
	 * Contract version.
	 *
	 * <p>
	 * Left empty, {@link NodeSpecHarvester} falls back to the jar manifest's
	 * {@code Implementation-Version}, which Maven fills — which is how {@code 1.0.0-SNAPSHOT} appears
	 * without anyone writing it anywhere. Unversioned is legal and simply disables skew detection.
	 * </p>
	 */
	String version() default "";

	/** Default maximum concurrent executions. */
	int defaultConcurrency() default 1;

	/** Default execution mode. */
	NodeMode defaultMode() default NodeMode.PARALLEL;

	/** Whether downstream nodes block on this node by default. */
	boolean defaultBlocking() default true;

	/**
	 * Events the editor can visualise. Empty means the standard five
	 * ({@code NODE_STARTED}, {@code NODE_COMPLETED}, {@code NODE_FAILED}, {@code NODE_SKIPPED},
	 * {@code NODE_STATS}), which is what all but a handful of nodes want.
	 */
	String[] events() default {};

	/**
	 * Whether this node derives extra ports from its options through a {@code NodePortResolver}.
	 *
	 * <p>
	 * The resolver itself stays hand-written and lives on both sides for built-in nodes. An
	 * <em>announced</em> node that sets this is accepted, but resolves to its declared static ports:
	 * its resolver class exists only on the worker.
	 * </p>
	 */
	boolean dynamicPorts() default false;

	/**
	 * The options class whose fields become parameters.
	 *
	 * <p>
	 * Normally inferred from the node's generic superclass, which is why it is almost never set. Give
	 * it explicitly when the node's hierarchy erases the type — a raw superclass, or a node that
	 * implements {@code CortexNode} directly.
	 * </p>
	 */
	Class<?> optionsClass() default void.class;

	/** Groups over the input ports (XOR alternatives). */
	PortGroupDoc[] inputGroups() default {};

	/** Groups over the output ports (EXCLUSIVE selections). */
	PortGroupDoc[] outputGroups() default {};

	/**
	 * Per-node documentation for options fields this node <em>inherits</em> rather than declares.
	 *
	 * <p>
	 * A subclass cannot re-annotate a superclass field, so a {@link ParamDoc} on a shared base applies
	 * to every node at once. This is the escape hatch for the cases where that is wrong — see
	 * {@link ParamOverride}.
	 * </p>
	 */
	ParamOverride[] parameters() default {};
}
