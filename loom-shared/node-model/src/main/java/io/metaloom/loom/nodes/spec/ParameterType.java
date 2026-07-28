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
	JSON
}
