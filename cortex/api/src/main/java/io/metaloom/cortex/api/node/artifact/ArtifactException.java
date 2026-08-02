package io.metaloom.cortex.api.node.artifact;

/**
 * An {@link ArtifactFactory} failed to produce its artifact.
 *
 * <p>
 * Unchecked because {@code PipelineNode.process} does not declare a checked exception, and because the runner already turns anything thrown out of a
 * node into a {@code FAILED} result for that node alone. The cause is the factory's original failure.
 * </p>
 */
public class ArtifactException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ArtifactException(String message, Throwable cause) {
		super(message, cause);
	}

	public ArtifactException(String message) {
		super(message);
	}
}
