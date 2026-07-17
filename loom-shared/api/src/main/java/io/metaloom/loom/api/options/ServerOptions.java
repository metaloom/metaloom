package io.metaloom.loom.api.options;

public class ServerOptions implements Option {

	public static final int DEFAULT_GRPC_PORT = 8091;

	public static final int DEFAULT_REST_PORT = 8092;

	public static final int DEFAULT_MONITORING_PORT = 8989;

	public static final int DEFAULT_MCP_PORT = 4041;

	public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

	@EnvironmentVariable(name = "LOOM_SERVER_GRPC_PORT", description = "Override the gRPC server port.")
	private int grpcPort = DEFAULT_GRPC_PORT;

	@EnvironmentVariable(name = "LOOM_SERVER_GRPC_BIND_ADDRESS", description = "Override the gRPC server bind address.")
	private String bindAddress = DEFAULT_BIND_ADDRESS;

	@EnvironmentVariable(name = "LOOM_SERVER_REST_PORT", description = "Override the REST server port.")
	private int restPort = DEFAULT_REST_PORT;

	@EnvironmentVariable(name = "LOOM_SERVER_MON_PORT", description = "Override the monitoring server port.")
	private int monitoringPort = DEFAULT_MONITORING_PORT;

	@EnvironmentVariable(name = "LOOM_SERVER_MCP_PORT", description = "Override the MCP server port.")
	private int mcpPort = DEFAULT_MCP_PORT;

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

	public int getMcpPort() {
		return mcpPort;
	}

	public ServerOptions setMcpPort(int mcpPort) {
		this.mcpPort = mcpPort;
		return this;
	}

	public String getBindAddress() {
		return bindAddress;
	}

	public ServerOptions setBindAddress(String bindAddress) {
		this.bindAddress = bindAddress;
		return this;
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnvInt("LOOM_SERVER_GRPC_PORT", this::setGrpcPort);
		OptionUtils.applyEnv("LOOM_SERVER_GRPC_BIND_ADDRESS", this::setBindAddress);
		OptionUtils.applyEnvInt("LOOM_SERVER_REST_PORT", this::setRestPort);
		OptionUtils.applyEnvInt("LOOM_SERVER_MON_PORT", this::setMonitoringPort);
		OptionUtils.applyEnvInt("LOOM_SERVER_MCP_PORT", this::setMcpPort);
	}
}
