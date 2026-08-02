package io.metaloom.loom.nodes.spec;

/**
 * Data type for a node parameter. Used by the UI to render the appropriate form control.
 */
public enum ParameterType {

	STRING,

	INTEGER,

	NUMBER,

	BOOLEAN,

	ENUM,

	ENUM_SET,

	/**
	 * Multi-line source code. Rendered as a monospace code editor rather than a single-line
	 * field. Used by the {@code script} node for the script body - a script in a one-line
	 * text input is unusable.
	 */
	CODE,

	/**
	 * A structured JSON value (object or array) rather than a scalar. Rendered as a
	 * multi-line monospace field that parses on edit. Used for parameters whose shape is a
	 * list or a nested bag - the {@code script} node's output declarations and its free-form
	 * parameter bag.
	 */
	JSON,

	/**
	 * A list of rows whose {@code id}s become the node's output ports. Rendered as a repeatable
	 * row editor with an add button, not as raw JSON text.
	 *
	 * <p>
	 * The distinction from {@link #JSON} is load-bearing rather than cosmetic. A {@code JSON}
	 * parameter commits the raw text on every keystroke, so a half-typed value parses to nothing
	 * and every derived handle on the node disappears until the text is valid again. A parameter
	 * that <em>defines ports</em> cannot behave that way: the editor for this type always emits a
	 * structurally valid array, so the resolver can drop an incomplete row without disturbing the
	 * others.
	 * </p>
	 *
	 * <p>
	 * Each row is <code>{"id": ..., "label": ..., "match": ...}</code>; only {@code id} is required
	 * and it must match {@link PortSpec#ID_PATTERN}. Used by the {@code filter} node's buckets.
	 * </p>
	 */
	PORT_LIST
}
