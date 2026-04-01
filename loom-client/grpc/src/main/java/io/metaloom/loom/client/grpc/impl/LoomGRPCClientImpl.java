package io.metaloom.loom.client.grpc.impl;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.metaloom.loom.client.grpc.AbstractLoomGRPCClient;

/**
 * Implementation of the {@link io.metaloom.loom.client.grpc.LoomGRPCClient}.
 */
public class LoomGRPCClientImpl extends AbstractLoomGRPCClient {

	public static final Logger log = LoggerFactory.getLogger(LoomGRPCClientImpl.class);

	private ManagedChannel channel;

	/**
	 * Create a new client with a default timeout of 10s for all timeouts (connect, read and write).
	 * 
	 * @param hostname
	 * @param port
	 * @param connectTimeout
	 * @param readTimeout
	 * @param writeTimeout
	 */
	protected LoomGRPCClientImpl(String hostname, int port, Duration connectTimeout, Duration readTimeout, Duration writeTimeout) {
		super(hostname, port, connectTimeout, readTimeout, writeTimeout);
	}

	public static Builder builder() {
		return new Builder();
	}

	public LoomGRPCClientImpl init() {
		channel = Grpc.newChannelBuilderForAddress(hostname, port, InsecureChannelCredentials.create())
			.userAgent(userAgent())
			// .usePlaintext()
			.build();
		// ManagedChannelBuilder.forAddress(hostname, port)
		// .userAgent(LoomGRPCClient.USER_AGENT)
		// .usePlaintext()
		// .build();
		return this;
	}

	private String userAgent() {
		// TODO add version via Loom or properties
		return USER_AGENT;
	}

	@Override
	public void close() {
		if (channel != null) {
			channel.shutdown();
		} else {
			log.warn("gRPC channel has not been initialized.");
		}
	}

	@Override
	public ManagedChannel channel() {
		return channel;
	}

	public static class Builder {

		private String hostname = "localhost";
		private int port = 6334;

		private Duration connectTimeout = Duration.ofMillis(10_000);
		private Duration readTimeout = Duration.ofMillis(10_000);
		private Duration writeTimeout = Duration.ofMillis(10_000);

		/**
		 * Verify the builder and build the client.
		 * 
		 * @return
		 */
		public LoomGRPCClientImpl build() {
			Objects.requireNonNull(hostname, "A hostname has to be specified.");

			LoomGRPCClientImpl client = new LoomGRPCClientImpl(hostname, port,
				connectTimeout, readTimeout, writeTimeout);
			client.init();
			return client;
		}

		/**
		 * Set the hostname for the client.
		 * 
		 * @param hostname
		 * @return Fluent API
		 */
		public Builder setHostname(String hostname) {
			this.hostname = hostname;
			return this;
		}

		/**
		 * Set the port to connect to. (e.g. 6334).
		 * 
		 * @param port
		 * @return Fluent API
		 */
		public Builder setPort(int port) {
			this.port = port;
			return this;
		}

		/**
		 * Set connection timeout.
		 * 
		 * @param connectTimeout
		 * @return Fluent API
		 */
		public Builder setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
			return this;
		}

		/**
		 * Set read timeout for the client.
		 * 
		 * @param readTimeout
		 * @return Fluent API
		 */
		public Builder setReadTimeout(Duration readTimeout) {
			this.readTimeout = readTimeout;
			return this;
		}

		/**
		 * Set write timeout for the client.
		 * 
		 * @param writeTimeout
		 * @return Fluent API
		 */
		public Builder setWriteTimeout(Duration writeTimeout) {
			this.writeTimeout = writeTimeout;
			return this;
		}

	}
}
