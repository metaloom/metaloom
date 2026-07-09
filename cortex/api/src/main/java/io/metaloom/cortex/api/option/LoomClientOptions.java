package io.metaloom.cortex.api.option;

public class LoomClientOptions {

	private String hostname;
	private int port;
	private String token;

	public int getPort() {
		return port;
	}

	public LoomClientOptions setPort(int port) {
		this.port = port;
		return this;
	}

	public String getHostname() {
		return hostname;
	}

	public LoomClientOptions setHostname(String hostname) {
		this.hostname = hostname;
		return this;
	}

	/**
	 * Bearer token used to authenticate WebSocket handshakes with Loom
	 * (see {@code /api/v1/processors/ws}). When not set the token is also
	 * read from the {@code LOOM_TOKEN} environment variable.
	 */
	public String getToken() {
		return token;
	}

	public LoomClientOptions setToken(String token) {
		this.token = token;
		return this;
	}

}
