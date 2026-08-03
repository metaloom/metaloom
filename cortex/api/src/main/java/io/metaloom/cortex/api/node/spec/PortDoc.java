package io.metaloom.cortex.api.node.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The authored half of one port, on the {@code InputPort}/{@code OutputPort} constant itself.
 *
 * <p>
 * The constant already carries the id, the content type, the cardinality and the value type — the
 * harvester reads those. This annotation adds what the editor shows a human, plus the three
 * behavioural bits the port record has no room for: whether the port must be wired, whether it
 * routes, and which group it belongs to.
 * </p>
 *
 * <p>
 * The annotation is optional. A port without one is still harvested; it simply gets a label derived
 * from its id and no description, which is worse for the author reading the palette but never wrong.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PortDoc {

	/** Label shown by the editor. Empty derives one by title-casing the port id. */
	String label() default "";

	/** What this port carries, shown on hover. */
	String description() default "";

	/**
	 * Whether this input must be wired for the node to execute.
	 *
	 * <p>
	 * Ignored for a port in a {@link #group()} — the group owns the requirement, which is the whole
	 * point of an XOR group: exactly one of its members is needed, so no single member can be.
	 * </p>
	 */
	boolean required() default true;

	/**
	 * Output side only: this port carries data for some items and not others, so the engine skips a
	 * consumer wired to it for the items where nothing was emitted.
	 *
	 * <p>
	 * Opt-in per port, never inferred. Leaving a declared output unwritten is normal and harmless —
	 * {@code facedetect} finds no faces, a {@code script} does not set every key — so a node only
	 * routes if it says it routes.
	 * </p>
	 */
	boolean selective() default false;

	/** Id of the {@code PortGroup} this port belongs to; must name a group declared on the same side. */
	String group() default "";

	/**
	 * Keep this port out of the <em>static</em> contract.
	 *
	 * <p>
	 * For a {@code dynamicPorts} node whose real ports come from a {@code NodePortResolver}. The node
	 * still declares the constants because it executes against them — {@code FilterNode} writes to
	 * {@code OUT_PASSED} — but the descriptor's static port list must be empty, because the resolver is
	 * what produces the actual ports from the node's options. Without this the same three ports would
	 * be advertised twice: once statically and once per configured bucket.
	 * </p>
	 */
	boolean hidden() default false;

	/**
	 * Explicit ordering within the node's input (or output) list.
	 *
	 * <p>
	 * Ports are otherwise emitted in field-declaration order, which is what the JVM reports for
	 * {@code getDeclaredFields()} in practice but does not promise. Set this only where the order
	 * matters and reflection got it wrong — it is an escape hatch, not the normal path.
	 * </p>
	 */
	int order() default Integer.MAX_VALUE;
}
