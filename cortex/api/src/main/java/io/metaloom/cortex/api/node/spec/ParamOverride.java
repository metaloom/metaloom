package io.metaloom.cortex.api.node.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * Re-documents one parameter from the node class, for fields the node itself does not declare.
 *
 * <p>
 * {@link ParamDoc} sits on the field, which is the right place for a field the node owns. It is the
 * wrong place — and an impossible one — for a field the node <em>inherits</em>: a subclass cannot
 * re-annotate a superclass field, so whatever the base said applies to all 34 nodes at once.
 * </p>
 *
 * <p>
 * It bites in two shapes, both real:
 * </p>
 *
 * <ul>
 * <li><strong>An inherited base.</strong> {@code timeoutMs} on {@code AbstractNodeOptions} is hidden,
 * because almost no descriptor advertises it — except {@code tts} and {@code depthmap}, which both do,
 * with different defaults, different bounds and different prose. Without an override those two nodes
 * silently lose a parameter their users have.</li>
 * <li><strong>Two siblings sharing one options class.</strong> {@code FacedetectNode} and
 * {@code FacedescriptionNode} both bind {@code FacedetectNodeOptions}, but only the first advertises
 * the detection knobs. A {@code @ParamDoc} on the shared class necessarily speaks for both, so the
 * quieter sibling hides what it does not want with {@code @ParamOverride(hidden = true)}.</li>
 * </ul>
 *
 * <pre>{@code
 * @NodeSpec(nodeId = "depthmap", ...,
 *     parameters = @ParamOverride(key = "timeoutMs", label = "Timeout (ms)",
 *         description = "HTTP request timeout", min = "1", order = 40))
 * }</pre>
 *
 * <p>
 * Declaring an override for a key <strong>includes</strong> that parameter, even when the inherited
 * {@link ParamDoc} hides it. Set {@link #hidden()} to go the other way and drop a field the base does
 * not hide.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface ParamOverride {

	/** The options field name this override applies to. An unmatched key is ignored. */
	String key();

	/** @see ParamDoc#label() */
	String label() default "";

	/** @see ParamDoc#description() */
	String description() default "";

	/** @see ParamDoc#type() */
	ParameterType[] type() default {};

	/** @see ParamDoc#min() */
	String min() default "";

	/** @see ParamDoc#max() */
	String max() default "";

	/** @see ParamDoc#step() */
	String step() default "";

	/** @see ParamDoc#values() */
	String[] values() default {};

	/** @see ParamDoc#language() */
	String language() default "";

	/** @see ParamDoc#rows() */
	int rows() default 0;

	/** Drop this parameter from the contract, for a field the inherited annotation does not hide. */
	boolean hidden() default false;

	/**
	 * Advertise no default, even though the field has one.
	 *
	 * <p>
	 * Rare and worth a second look when you reach for it: the descriptor's default is what the editor
	 * pre-fills, so omitting one the node will actually apply means the form shows an empty field and
	 * the node quietly uses something else. It exists because a hand-written descriptor did that, and
	 * a mechanical sweep must be able to preserve behaviour exactly rather than quietly "fix" it in
	 * passing.
	 * </p>
	 */
	boolean omitDefault() default false;

	/** @see ParamDoc#defaultValue() */
	String defaultValue() default "";

	/** @see ParamDoc#order() */
	int order() default Integer.MAX_VALUE;
}
