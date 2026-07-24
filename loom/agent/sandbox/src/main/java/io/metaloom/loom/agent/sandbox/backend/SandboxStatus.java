package io.metaloom.loom.agent.sandbox.backend;

/**
 * A point-in-time status of a Session Runner as reported by its backend.
 *
 * @param phase
 *            backend phase, e.g. {@code Pending}, {@code Running}, {@code Failed}, {@code Gone}
 * @param endpoint
 *            the resolved base http url once known, otherwise {@code null}
 * @param createdAtEpochMs
 *            creation timestamp in epoch millis (0 if unknown)
 */
public record SandboxStatus(String phase, String endpoint, long createdAtEpochMs) {

	public boolean isTerminal() {
		return "Failed".equals(phase) || "Gone".equals(phase);
	}
}
