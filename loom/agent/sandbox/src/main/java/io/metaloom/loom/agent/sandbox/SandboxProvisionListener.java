package io.metaloom.loom.agent.sandbox;

/**
 * Notified when a Session Runner has become healthy, so extra content can be pushed into it.
 *
 * <p>This exists so the orchestrator stays unaware of what gets pushed. The agent memory bank implements it to materialize the caller's notes into the
 * read-only {@code /memory} folder; the sandbox module itself binds an empty set of listeners.</p>
 *
 * <p>Implementations are called on a worker thread and must be best-effort: a failure here must never fail provisioning, because the tools that do the same
 * job over the API remain available either way.</p>
 */
public interface SandboxProvisionListener {

	void onProvisioned(String session, SandboxClient client);

}
