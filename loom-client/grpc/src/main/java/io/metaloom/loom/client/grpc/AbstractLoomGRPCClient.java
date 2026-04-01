package io.metaloom.loom.client.grpc;

import java.time.Duration;

public abstract class AbstractLoomGRPCClient implements LoomGRPCClient {

	protected final String hostname;
	protected final int port;
	protected String token;

	protected final Duration connectTimeout;
	protected final Duration readTimeout;
	protected final Duration writeTimeout;

	/**
	 * @param hostname
	 * @param port
	 * @param connectTimeout
	 * @param readTimeout
	 * @param writeTimeout
	 */
	protected AbstractLoomGRPCClient(String hostname, int port, Duration connectTimeout, Duration readTimeout, Duration writeTimeout) {
		this.hostname = hostname;
		this.port = port;

		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.writeTimeout = writeTimeout;
	}

	@Override
	public int getPort() {
		return port;
	}

	@Override
	public String getHostname() {
		return hostname;
	}

	@Override
	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	@Override
	public Duration getReadTimeout() {
		return readTimeout;
	}

	@Override
	public Duration getWriteTimeout() {
		return writeTimeout;
	}

	@Override
	public void setToken(String token) {
		this.token = token;
	}

	@Override
	public String token() {
		return token;
	}
}
