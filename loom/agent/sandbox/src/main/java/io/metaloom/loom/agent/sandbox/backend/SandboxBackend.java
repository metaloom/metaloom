package io.metaloom.loom.agent.sandbox.backend;

/**
 * A pluggable container runtime that can start, inspect and stop a single hardened "Session Runner".
 *
 * <p>Two implementations exist: {@link PodmanBackend} (rootless podman, local dev) and
 * {@link KubernetesBackend} (kubernetes/openshift, production). The orchestrator never knows which one
 * is active. All runtime hardening (read-only rootfs, dropped caps, non-root, pids/mem/cpu limits) is
 * applied by the backend at create time, not baked into the image.</p>
 */
public interface SandboxBackend {

	/**
	 * Create and start a Session Runner.
	 *
	 * @param spec
	 *            what to create — identity, token and whether the memory volume is provisioned
	 * @return info about the started runner
	 */
	SandboxInfo create(SandboxSpec spec);

	/**
	 * Delete/stop a Session Runner. Must be idempotent — deleting a missing runner is a no-op.
	 */
	void delete(String session, String podName);

	/**
	 * Report the current status of a runner (used while waiting for a kubernetes pod to be scheduled).
	 */
	SandboxStatus status(String podName);
}
