package io.metaloom.loom.api.options;

public class ServerOptions implements Option {

	public static final int DEFAULT_GRPC_PORT = 8091;

	public static final int DEFAULT_REST_PORT = 8092;

	public static final int DEFAULT_MONITORING_PORT = 8989;

	public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

	private int grpcPort = DEFAULT_GRPC_PORT;

	private String bindAddress = DEFAULT_BIND_ADDRESS;

	private int restPort = DEFAULT_REST_PORT;

	private int monitoringPort = DEFAULT_MONITORING_PORT;

	public int getGrpcPort() {
		return grpcPort;
	}

	public ServerOptions setGrpcPort(int grpcPort) {
		this.grpcPort = grpcPort;
		return this;
	}

	public int getMonitoringPort() {
		return monitoringPort;
	}

	public ServerOptions setMonitoringPort(int monitoringPort) {
		this.monitoringPort = monitoringPort;
		return this;
	}

	public int getRestPort() {
		return restPort;
	}

	public ServerOptions setRestPort(int restPort) {
		this.restPort = restPort;
		return this;
	}

	public String getBindAddress() {
		return bindAddress;
	}

	public ServerOptions setBindAddress(String bindAddress) {
		this.bindAddress = bindAddress;
		return this;
	}
}
