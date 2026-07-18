package io.metaloom.loom.pipeline.graph;

/**
 * Thrown when a pipeline definition cannot be turned into an executable graph.
 *
 * <p>Deliberately fatal. The failure mode this replaces was silent: a definition
 * the engine could not understand used to degrade into a smaller graph that ran
 * green while doing almost nothing. A pipeline that cannot execute as drawn must
 * say so.</p>
 */
public class GraphValidationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public GraphValidationException(String message) {
		super(message);
	}

	public GraphValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
