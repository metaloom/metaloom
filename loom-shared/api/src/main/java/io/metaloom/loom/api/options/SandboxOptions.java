package io.metaloom.loom.api.options;

/**
 * Options which control the isolated per-session coding sandbox used by the agentic loop.
 *
 * <p>When {@link #isEnabled()} is set the chat agent gains the coding tools (run_shell / write_file /
 * read_file / list_files) which are executed inside a hardened, per-chat "Session Runner" container
 * (podman for local dev, kubernetes/openshift for production) — never in the Loom process itself.</p>
 */
public class SandboxOptions implements Option {

	public static final String BACKEND_PODMAN = "podman";

	public static final String BACKEND_KUBERNETES = "kubernetes";

	public static final String DEFAULT_BACKEND = BACKEND_PODMAN;

	public static final String DEFAULT_IMAGE = "metaloom/loom-session-runner:latest";

	public static final int DEFAULT_IDLE_TTL_S = 900;

	public static final int DEFAULT_MAX_SESSION_S = 3600;

	public static final int DEFAULT_EXEC_TIMEOUT_S = 120;

	public static final int DEFAULT_MAX_CONCURRENT = 10;

	public static final int DEFAULT_READY_TIMEOUT_S = 60;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_ENABLED", description = "Enable the isolated coding sandbox (run_shell/write_file/read_file/list_files tools).")
	private boolean enabled = false;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_BACKEND", description = "Container backend used for the Session Runner (podman, kubernetes).")
	private String backend = DEFAULT_BACKEND;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_IMAGE", description = "Container image reference for the Session Runner.")
	private String image = DEFAULT_IMAGE;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_NAMESPACE", description = "Kubernetes/OpenShift namespace for Session Runner pods (falls back to the service-account namespace).")
	private String namespace = "";

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_IDLE_TTL_S", description = "Reap a Session Runner after this many seconds of inactivity.")
	private int idleTtlSeconds = DEFAULT_IDLE_TTL_S;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_MAX_SESSION_S", description = "Hard time-box for a Session Runner in seconds; then it is terminated and the client notified.")
	private int maxSessionSeconds = DEFAULT_MAX_SESSION_S;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_EXEC_TIMEOUT_S", description = "Default per-exec wall-clock timeout in seconds.")
	private int execTimeoutSeconds = DEFAULT_EXEC_TIMEOUT_S;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_MAX_CONCURRENT", description = "Per-deployment cap on concurrently running Session Runners (excess provision requests fail as retryable).")
	private int maxConcurrent = DEFAULT_MAX_CONCURRENT;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_READY_TIMEOUT_S", description = "How long to wait for a freshly provisioned Session Runner to become healthy, in seconds.")
	private int readyTimeoutSeconds = DEFAULT_READY_TIMEOUT_S;

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_CPU_REQUEST", description = "CPU request applied to each Session Runner.")
	private String cpuRequest = "100m";

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_CPU_LIMIT", description = "CPU limit applied to each Session Runner.")
	private String cpuLimit = "1";

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_MEM_REQUEST", description = "Memory request applied to each Session Runner.")
	private String memRequest = "128Mi";

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_MEM_LIMIT", description = "Memory limit applied to each Session Runner.")
	private String memLimit = "512Mi";

	@EnvironmentVariable(name = "LOOM_AGENT_SANDBOX_WORKSPACE_SIZE", description = "Size limit of the ephemeral /workspace volume.")
	private String workspaceSize = "512Mi";

	public boolean isEnabled() {
		return enabled;
	}

	public SandboxOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getBackend() {
		return backend;
	}

	public SandboxOptions setBackend(String backend) {
		this.backend = backend;
		return this;
	}

	public String getImage() {
		return image;
	}

	public SandboxOptions setImage(String image) {
		this.image = image;
		return this;
	}

	public String getNamespace() {
		return namespace;
	}

	public SandboxOptions setNamespace(String namespace) {
		this.namespace = namespace;
		return this;
	}

	public int getIdleTtlSeconds() {
		return idleTtlSeconds;
	}

	public SandboxOptions setIdleTtlSeconds(int idleTtlSeconds) {
		this.idleTtlSeconds = idleTtlSeconds;
		return this;
	}

	public int getMaxSessionSeconds() {
		return maxSessionSeconds;
	}

	public SandboxOptions setMaxSessionSeconds(int maxSessionSeconds) {
		this.maxSessionSeconds = maxSessionSeconds;
		return this;
	}

	public int getExecTimeoutSeconds() {
		return execTimeoutSeconds;
	}

	public SandboxOptions setExecTimeoutSeconds(int execTimeoutSeconds) {
		this.execTimeoutSeconds = execTimeoutSeconds;
		return this;
	}

	public int getMaxConcurrent() {
		return maxConcurrent;
	}

	public SandboxOptions setMaxConcurrent(int maxConcurrent) {
		this.maxConcurrent = maxConcurrent;
		return this;
	}

	public int getReadyTimeoutSeconds() {
		return readyTimeoutSeconds;
	}

	public SandboxOptions setReadyTimeoutSeconds(int readyTimeoutSeconds) {
		this.readyTimeoutSeconds = readyTimeoutSeconds;
		return this;
	}

	public String getCpuRequest() {
		return cpuRequest;
	}

	public SandboxOptions setCpuRequest(String cpuRequest) {
		this.cpuRequest = cpuRequest;
		return this;
	}

	public String getCpuLimit() {
		return cpuLimit;
	}

	public SandboxOptions setCpuLimit(String cpuLimit) {
		this.cpuLimit = cpuLimit;
		return this;
	}

	public String getMemRequest() {
		return memRequest;
	}

	public SandboxOptions setMemRequest(String memRequest) {
		this.memRequest = memRequest;
		return this;
	}

	public String getMemLimit() {
		return memLimit;
	}

	public SandboxOptions setMemLimit(String memLimit) {
		this.memLimit = memLimit;
		return this;
	}

	public String getWorkspaceSize() {
		return workspaceSize;
	}

	public SandboxOptions setWorkspaceSize(String workspaceSize) {
		this.workspaceSize = workspaceSize;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		// Sensible defaults for every field; nothing is strictly required unless the sandbox is enabled,
		// and even then the backend adapters surface their own configuration errors at provision time.
	}

}
