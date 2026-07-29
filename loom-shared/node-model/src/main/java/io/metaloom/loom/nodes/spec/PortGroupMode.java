package io.metaloom.loom.nodes.spec;

/**
 * How the members of a {@link PortGroup} relate to one another.
 */
public enum PortGroupMode {

	/**
	 * Alternatives on the input side: <strong>exactly one</strong> member must be wired when the group is required, at most one otherwise.
	 *
	 * <p>
	 * This is what {@code whisper} and {@code facedetect} always meant. Both used to declare two input connectors named {@code media} — one
	 * {@code media/audio} and one {@code media/video} — which the editor could not tell apart. They were never two inputs; they were one input with two
	 * alternatives.
	 * </p>
	 */
	XOR,

	/**
	 * Mutually exclusive outputs: <strong>at most one</strong> member may be wired. Selecting one renders its siblings inoperable, and emitting a
	 * non-selected member at runtime fails the task.
	 */
	EXCLUSIVE
}
