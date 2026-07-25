package io.metaloom.loom.agent.sandbox.backend;

/**
 * Everything a backend needs to create one Session Runner.
 *
 * @param session
 *            the logical session id (the chat uuid) — used only for labelling
 * @param name
 *            the unique container/pod name to assign
 * @param token
 *            the per-session bearer token injected as {@code RUNNER_TOKEN}
 * @param memoryStage
 *            whether to provision the memory volume. It is mounted <b>twice</b>: read-write at the stage path, which only {@code runnerd} uses, and
 *            read-only at the agent-visible mount path. Both refer to the same data, so a note written through the stage is immediately readable — and the
 *            agent's own shell gets {@code EROFS} on the read-only view. {@code runnerd} cannot enforce that itself: it runs unprivileged with all
 *            capabilities dropped, so a bind remount is impossible and a same-uid {@code chmod} would be trivially reversible.
 * @param memoryMountPath
 *            the read-only path the memory folder is exposed at (e.g. {@code /memory})
 */
public record SandboxSpec(String session, String name, String token, boolean memoryStage, String memoryMountPath) {

	/** Read-write path only {@code runnerd} sees; the same volume as {@link #memoryMountPath()}. */
	public static final String MEMORY_STAGE_PATH = "/var/lib/loom-memory";

	public SandboxSpec {
		memoryMountPath = memoryMountPath == null || memoryMountPath.isBlank() ? "/memory" : memoryMountPath;
	}

	/**
	 * A spec without the memory volume.
	 */
	public static SandboxSpec of(String session, String name, String token) {
		return new SandboxSpec(session, name, token, false, null);
	}

}
