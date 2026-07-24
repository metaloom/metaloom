package io.metaloom.loom.agent.sandbox.backend;

/**
 * Result of a backend {@code create} call.
 *
 * @param podName
 *            the backend-assigned container/pod name
 * @param endpoint
 *            the base http url of the runner ({@code http://host:port}); may be {@code null} for
 *            backends (kubernetes) where the pod IP only becomes known once the pod is scheduled — in
 *            that case the orchestrator polls {@link SandboxBackend#status(String)} to resolve it
 * @param createdAtEpochMs
 *            creation timestamp in epoch millis
 */
public record SandboxInfo(String podName, String endpoint, long createdAtEpochMs) {
}
