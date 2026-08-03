package io.metaloom.cortex.api.node.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.metaloom.loom.nodes.spec.PortGroupMode;

/**
 * One port group, declared inside {@code @NodeSpec(inputGroups = ...)} or {@code outputGroups}.
 *
 * <p>
 * A group is the only part of the port model that has nowhere to live on the port constants
 * themselves: it is a statement about a <em>set</em> of ports. {@code whisper} declaring "either a
 * media file or an audio artifact, but not both" is one XOR group over two inputs, and neither input
 * alone can express it.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface PortGroupDoc {

	/** Group id, referenced by {@code @PortDoc(group = ...)} on each member. */
	String id();

	/** How the members relate: XOR alternatives on the input side, exclusive selection on the output side. */
	PortGroupMode mode();

	/** Whether one member must be wired. */
	boolean required() default true;

	/** Label shown by the editor. */
	String label() default "";
}
