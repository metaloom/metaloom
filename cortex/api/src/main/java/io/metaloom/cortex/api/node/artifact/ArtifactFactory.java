package io.metaloom.cortex.api.node.artifact;

/**
 * Produces an artifact when no node has produced it yet.
 *
 * <p>
 * Allowed to throw: decoding fails, files vanish. A factory that throws publishes <strong>nothing</strong> — the scope stores no entry and the next
 * node asking for the same key runs the factory again rather than inheriting a half-built object.
 * </p>
 *
 * @param <T>
 *            the artifact type
 */
@FunctionalInterface
public interface ArtifactFactory<T> {

	/**
	 * Build the artifact and state what it weighs.
	 */
	Artifact<T> create() throws Exception;
}
