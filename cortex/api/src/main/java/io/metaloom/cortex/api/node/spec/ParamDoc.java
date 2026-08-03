package io.metaloom.cortex.api.node.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.metaloom.loom.nodes.spec.ParameterType;

/**
 * The authored half of one parameter, on the options field itself.
 *
 * <p>
 * The field already carries the parameter key (its name), its type and its default (the value a
 * default-constructed options instance holds). This annotation adds the label, the help text and the
 * bounds — intent that no amount of reflection can recover.
 * </p>
 *
 * <p>
 * A field with no annotation is still harvested. Annotate a field with {@link #hidden()} to keep it
 * out of the contract entirely — for a knob that is worker-scoped operational configuration rather
 * than something a pipeline author sets.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ParamDoc {

	/** Label for the form field. Empty derives one by title-casing the field name. */
	String label() default "";

	/** Help text shown under the field. */
	String description() default "";

	/**
	 * Override the parameter type.
	 *
	 * <p>
	 * Declared as an array purely so that "unset" is expressible: empty means "infer from the field's
	 * Java type", which is right for every scalar. Set it where the Java type under-describes the
	 * intent — a {@code String} holding a script body is {@link ParameterType#CODE}, a {@code String}
	 * holding an object is {@link ParameterType#JSON}, a {@code List<String>} of choices is
	 * {@link ParameterType#ENUM_SET}.
	 * </p>
	 */
	ParameterType[] type() default {};

	/**
	 * Minimum value, as it should appear in the contract.
	 *
	 * <p>
	 * A string, and deliberately so: {@code "1"} must serialize as {@code 1} and {@code "0.0"} as
	 * {@code 0.0}. A single numeric annotation attribute would have to pick one, and would silently
	 * turn every integer bound in the palette into a float.
	 * </p>
	 */
	String min() default "";

	/** Maximum value. See {@link #min()} for why it is a string. */
	String max() default "";

	/** Step increment for the editor's number control. See {@link #min()}. */
	String step() default "";

	/**
	 * Allowed values for an {@link ParameterType#ENUM} / {@link ParameterType#ENUM_SET} parameter.
	 *
	 * <p>
	 * Only needed when the field's Java type is not itself an enum — the harvester reads
	 * {@code Class.getEnumConstants()} when it is.
	 * </p>
	 */
	String[] values() default {};

	/** Syntax-highlighting hint for a {@link ParameterType#CODE} parameter, e.g. {@code "javascript"}. */
	String language() default "";

	/** Preferred editor height in rows for a code or JSON parameter. 0 leaves it unset. */
	int rows() default 0;

	/**
	 * Keep this field out of the contract entirely.
	 *
	 * <p>
	 * For state that is not a pipeline-author-facing knob. {@code timeoutMs} on the common options
	 * base is the canonical case: every node has it and almost no descriptor advertises it, so
	 * surfacing it by default would add a field to all 34 forms. {@code tts} and {@code depthmap} are
	 * the exceptions and re-document it with {@link ParamOverride} — which is why hiding on a shared
	 * base needs a per-node escape hatch rather than being final.
	 * </p>
	 */
	boolean hidden() default false;

	/**
	 * Advertise no default, even though the field has one.
	 *
	 * <p>
	 * Worth a second look whenever you reach for it: the descriptor's default is what the editor
	 * pre-fills, so omitting one the node will actually apply means the form shows an empty field while
	 * the node quietly uses something else. It exists because a hand-written descriptor did exactly
	 * that, and a mechanical sweep has to be able to preserve behaviour rather than silently "fix" it.
	 * </p>
	 */
	boolean omitDefault() default false;

	/**
	 * Advertise this default instead of the one the field holds.
	 *
	 * <p>
	 * Always a string, and always emitted as one — so it fits a {@link ParameterType#STRING},
	 * {@link ParameterType#CODE} or {@link ParameterType#JSON} parameter, where the editor pre-fills a
	 * text box. It exists for the case where the useful starting point for an author is not a legal
	 * value for the field: {@code script}'s body defaults to {@code null} because a node with no script
	 * must fail validation, while the form should open with a runnable example in it.
	 * </p>
	 *
	 * <p>
	 * Reach for {@link #omitDefault()} first. A default that disagrees with the field is a small lie the
	 * editor tells, and it is only worth telling when the field genuinely cannot hold the useful value.
	 * </p>
	 */
	String defaultValue() default "";

	/**
	 * Explicit ordering within the node's parameter list. Parameters are otherwise emitted with the
	 * inherited common ones first, then in field-declaration order.
	 */
	int order() default Integer.MAX_VALUE;
}
