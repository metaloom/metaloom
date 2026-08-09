package io.metaloom.loom.api.options;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Options which control ad-hoc ("pipelineless") node execution — running a node on chosen assets
 * without a stored pipeline, through {@code POST /api/v1/node-runs} and the MCP execution tools.
 *
 * <p>
 * Two different shapes of work are bounded here. A <b>probe</b> is one node over one asset, awaited
 * inside the caller's request, and its whole design depends on {@link #getProbeTimeoutMs()} staying
 * below {@link AiOptions#getToolTimeoutMs()}: the agent loop must see a readable "it did not finish"
 * result rather than have its own tool timeout fire. A <b>run</b> is a graph over many assets, is
 * answered with a job handle immediately, and is bounded by the three {@code MAX_*} caps instead.
 * </p>
 *
 * <p>
 * See {@code spec/chat/AGENTIC_NODE_EXECUTION.md}.
 * </p>
 */
public class NodeExecOptions implements Option {

	/**
	 * Deliberately under {@link AiOptions#DEFAULT_TOOL_TIMEOUT_MS}. A probe that overran the tool
	 * budget would surface to the model as a bare transport timeout with no node name in it, which it
	 * cannot act on; finishing first lets the tool say which kind was slow and what to use instead.
	 */
	public static final int DEFAULT_PROBE_TIMEOUT_MS = 25_000;

	/**
	 * Kinds that write to Loom themselves through {@code LoomClient} inside {@code process()}, which
	 * means {@code persist=false} cannot hold for them: Loom can decline to write the ledger, but it
	 * cannot un-write what a worker already sent. Until a node can declare that it writes, this list
	 * is the only thing standing between "probe this, nothing is recorded" and a silent catalog edit.
	 */
	public static final String DEFAULT_PROBE_DENY_KINDS = "metadata,tts,guard,watermark,translate";

	public static final int DEFAULT_MAX_ASSETS = 200;

	public static final int DEFAULT_MAX_NODES = 10;

	public static final int DEFAULT_MAX_ACTIVE_JOBS_PER_USER = 3;

	public static final int DEFAULT_RESULT_MAX_CHARS = 4_000;

	@EnvironmentVariable(name = "LOOM_AGENT_EXEC_ENABLED", description = "Enable ad-hoc node execution (the /api/v1/node-runs routes and the run_node_probe/run_node_graph/get_job/cancel_job MCP tools).")
	private boolean enabled = true;

	@EnvironmentVariable(name = "LOOM_AGENT_PROBE_TIMEOUT_MS", description = "Wall clock a single synchronous node probe may take. Must stay below LOOM_AI_TOOL_TIMEOUT_MS.")
	private int probeTimeoutMs = DEFAULT_PROBE_TIMEOUT_MS;

	@EnvironmentVariable(name = "LOOM_AGENT_PROBE_KINDS", description = "Comma-separated allow-list of probe-eligible node kinds. When empty the derived rule applies: any non-source, non-output kind that does not produce artifact bytes and is not denied.")
	private String probeKinds = "";

	@EnvironmentVariable(name = "LOOM_AGENT_PROBE_DENY_KINDS", description = "Comma-separated node kinds excluded from the derived probe rule because they write to Loom out of band.")
	private String probeDenyKinds = DEFAULT_PROBE_DENY_KINDS;

	@EnvironmentVariable(name = "LOOM_AGENT_EXEC_MAX_ASSETS", description = "Maximum number of assets a single ad-hoc node run may be started with.")
	private int maxAssets = DEFAULT_MAX_ASSETS;

	@EnvironmentVariable(name = "LOOM_AGENT_EXEC_MAX_NODES", description = "Maximum number of nodes an inline ad-hoc definition may contain, source included.")
	private int maxNodes = DEFAULT_MAX_NODES;

	@EnvironmentVariable(name = "LOOM_AGENT_EXEC_MAX_ACTIVE_JOBS_PER_USER", description = "Maximum number of non-terminal ad-hoc runs one user may have at a time.")
	private int maxActiveJobsPerUser = DEFAULT_MAX_ACTIVE_JOBS_PER_USER;

	@EnvironmentVariable(name = "LOOM_AGENT_EXEC_RESULT_MAX_CHARS", description = "Maximum size of the text an execution tool returns to the model. Truncation is announced in the text.")
	private int resultMaxChars = DEFAULT_RESULT_MAX_CHARS;

	@EnvironmentVariable(name = "LOOM_AGENT_EXEC_PERSIST_DEFAULT", description = "Default for the persist flag when a caller omits it. Off means an ad-hoc result is returned and nothing is written to the asset catalog.")
	private boolean persistDefault = false;

	public boolean isEnabled() {
		return enabled;
	}

	public NodeExecOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public int getProbeTimeoutMs() {
		return probeTimeoutMs;
	}

	public NodeExecOptions setProbeTimeoutMs(int probeTimeoutMs) {
		this.probeTimeoutMs = probeTimeoutMs;
		return this;
	}

	public String getProbeKinds() {
		return probeKinds;
	}

	public NodeExecOptions setProbeKinds(String probeKinds) {
		this.probeKinds = probeKinds;
		return this;
	}

	public String getProbeDenyKinds() {
		return probeDenyKinds;
	}

	public NodeExecOptions setProbeDenyKinds(String probeDenyKinds) {
		this.probeDenyKinds = probeDenyKinds;
		return this;
	}

	/**
	 * The allow-list as a set, empty when no allow-list is configured.
	 *
	 * <p>
	 * An empty set means "no allow-list", not "allow nothing" — the derived rule takes over. Setting
	 * the variable to a single kind is how an operator narrows execution to exactly that kind.
	 * </p>
	 */
	public Set<String> probeKindSet() {
		return splitKinds(probeKinds);
	}

	/** The deny-list as a set. */
	public Set<String> probeDenyKindSet() {
		return splitKinds(probeDenyKinds);
	}

	private static Set<String> splitKinds(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		Set<String> kinds = new LinkedHashSet<>();
		Arrays.stream(csv.split(","))
			.map(String::trim)
			.filter(kind -> !kind.isEmpty())
			.map(kind -> kind.toLowerCase(Locale.ROOT))
			.forEach(kinds::add);
		return kinds;
	}

	public int getMaxAssets() {
		return maxAssets;
	}

	public NodeExecOptions setMaxAssets(int maxAssets) {
		this.maxAssets = maxAssets;
		return this;
	}

	public int getMaxNodes() {
		return maxNodes;
	}

	public NodeExecOptions setMaxNodes(int maxNodes) {
		this.maxNodes = maxNodes;
		return this;
	}

	public int getMaxActiveJobsPerUser() {
		return maxActiveJobsPerUser;
	}

	public NodeExecOptions setMaxActiveJobsPerUser(int maxActiveJobsPerUser) {
		this.maxActiveJobsPerUser = maxActiveJobsPerUser;
		return this;
	}

	public int getResultMaxChars() {
		return resultMaxChars;
	}

	public NodeExecOptions setResultMaxChars(int resultMaxChars) {
		this.resultMaxChars = resultMaxChars;
		return this;
	}

	public boolean isPersistDefault() {
		return persistDefault;
	}

	public NodeExecOptions setPersistDefault(boolean persistDefault) {
		this.persistDefault = persistDefault;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (!enabled) {
			return;
		}
		errors.min("probeTimeoutMs", probeTimeoutMs, 1)
			.min("maxAssets", maxAssets, 1)
			.min("maxNodes", maxNodes, 1)
			.min("maxActiveJobsPerUser", maxActiveJobsPerUser, 1)
			.min("resultMaxChars", resultMaxChars, 1);
	}

}
