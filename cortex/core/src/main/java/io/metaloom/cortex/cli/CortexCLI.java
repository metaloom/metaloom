package io.metaloom.cortex.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import ch.qos.logback.classic.Level;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.LoomClientOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

@Singleton
@Command(name = "cortex", mixinStandardHelpOptions = false, version = "Cortex 1.0.0-SNAPSHOT", description = "Cortex is a media processing tool", showDefaultValues = true)
public class CortexCLI implements Runnable {

	public static final int DEFAULT_PORT = 7733;
	public static final String DEFAULT_PORT_STR = "7733";
	public static final String DEFAULT_HOSTNAME = "localhost";
	public static final int DEFAULT_MONITORING_PORT = 8093;
	public static final String DEFAULT_MONITORING_PORT_STR = "8093";
	public static final String DEFAULT_META_PATH = "${user.home}/.cache/metaloom/cortex/meta";

	private String hostname = DEFAULT_HOSTNAME;

	private int port = DEFAULT_PORT;

	private int monitoringPort = DEFAULT_MONITORING_PORT;

	private Path metaPath = Paths.get(System.getProperty("user.home"), ".cache", "metaloom", "cortex", "meta");

	/** Null means "generate one per process". */
	private String nodeId;

	/** Null means "announce everything this worker can run". */
	private Set<String> nodeKinds;

	@Spec
	CommandSpec spec;

	@Inject
	public CortexCLI() {
	}

	@Option(names = "-v", scope = ScopeType.INHERIT)
	public void setVerbose(boolean[] verbose) {
		Level level = Level.INFO;
		if (verbose.length > 0) {
			level = Level.DEBUG;
		}
		if (verbose.length >= 1) {
			level = Level.TRACE;
		}
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
		root.setLevel(level);
	}

	public String getHostname() {
		return hostname;
	}

	@Option(names = { "-h", "--hostname" }, description = "Loom server hostname. Env: LOOM_HOST", defaultValue = DEFAULT_HOSTNAME, scope = ScopeType.INHERIT)
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public int getPort() {
		return port;
	}

	@Option(names = { "-p", "--port" }, description = "Loom server HTTP port. Env: LOOM_PORT", defaultValue = DEFAULT_PORT_STR, scope = ScopeType.INHERIT)
	public void setPort(int port) {
		this.port = port;
	}

	public int getMonitoringPort() {
		return monitoringPort;
	}

	@Option(names = { "--monitoring-port" }, description = "Monitoring HTTP port. Env: CORTEX_MONITORING_PORT", defaultValue = DEFAULT_MONITORING_PORT_STR, scope = ScopeType.INHERIT)
	public void setMonitoringPort(int monitoringPort) {
		this.monitoringPort = monitoringPort;
	}

	public Path getMetaPath() {
		return metaPath;
	}

	@Option(names = { "--meta-path" }, description = "Base path for metadata storage. Env: CORTEX_META_PATH", defaultValue = DEFAULT_META_PATH, scope = ScopeType.INHERIT)
	public void setMetaPath(Path metaPath) {
		this.metaPath = metaPath;
	}

	public String getNodeId() {
		return nodeId;
	}

	@Option(names = { "--node-id" }, description = "Identity this worker registers under. Generated per process when unset. Env: CORTEX_NODE_ID", scope = ScopeType.INHERIT)
	public void setNodeId(String nodeId) {
		this.nodeId = nodeId;
	}

	public Set<String> getNodeKinds() {
		return nodeKinds;
	}

	@Option(names = { "--node-kinds" }, split = ",", description = "Node kinds this worker will execute, comma separated. Announces everything it can run when unset. Env: CORTEX_NODE_KINDS", scope = ScopeType.INHERIT)
	public void setNodeKinds(Set<String> nodeKinds) {
		this.nodeKinds = nodeKinds;
	}

	/**
	 * Build {@link CortexOptions} from the parsed CLI values.
	 */
	public CortexOptions toCortexOptions() {
		CortexOptions options = new CortexOptions();
		LoomClientOptions loom = new LoomClientOptions();
		loom.setHostname(hostname);
		loom.setPort(port);
		options.setLoom(loom);
		options.setMonitoringPort(monitoringPort);
		options.setMetaPath(metaPath);
		// Left null when unset - CortexOptions treats null as "no restriction", and
		// passing an empty set instead would read as "run nothing".
		options.setNodeId(nodeId);
		options.setNodeKinds(nodeKinds);
		return options;
	}

	@Override
	public void run() {
		spec.commandLine().usage(System.out);
	}

}
