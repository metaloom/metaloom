package io.metaloom.loom.agent.sandbox;

/**
 * Mutable registry entry tracking one live Session Runner for a session (chat uuid).
 */
public class SandboxHandle {

	private final String session;
	private final String podName;
	private final String endpoint;
	private final String token;
	private final long createdAt;
	private volatile long lastUsed;
	private volatile String phase;
	private final SandboxClient client;

	public SandboxHandle(String session, String podName, String endpoint, String token, long createdAt, SandboxClient client) {
		this.session = session;
		this.podName = podName;
		this.endpoint = endpoint;
		this.token = token;
		this.createdAt = createdAt;
		this.lastUsed = System.currentTimeMillis();
		this.phase = "Running";
		this.client = client;
	}

	public String session() {
		return session;
	}

	public String podName() {
		return podName;
	}

	public String endpoint() {
		return endpoint;
	}

	public String token() {
		return token;
	}

	public long createdAt() {
		return createdAt;
	}

	public long lastUsed() {
		return lastUsed;
	}

	public void touch() {
		this.lastUsed = System.currentTimeMillis();
	}

	public String phase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public SandboxClient client() {
		return client;
	}
}
