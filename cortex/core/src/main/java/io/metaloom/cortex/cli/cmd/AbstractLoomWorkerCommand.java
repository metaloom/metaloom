package io.metaloom.cortex.cli.cmd;

import static io.metaloom.cortex.cli.ExitCode.OK;

import io.metaloom.cortex.cli.CortexCLI;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command
public class AbstractLoomWorkerCommand implements LoomWorkerCommand {

	@Spec
	CommandSpec spec;

	@ParentCommand
	private CortexCLI parent;

	@Override
	public Integer call() {
		spec.commandLine().usage(System.out);
		return OK.code();
	}

	/**
	 * Enforce that a stable worker identity has been configured before the worker goes online.
	 *
	 * <p>A worker id is not optional for the server: Loom keys registration, node-kind
	 * restrictions and run attribution on it, and refuses a second worker that announces an
	 * id already in use. A generated-per-process id would defeat both - it would create a
	 * fresh {@code cortex_instance} on every restart and could collide with a live worker -
	 * so a missing id is a hard, up-front failure rather than a silent fallback.</p>
	 *
	 * @throws ParameterException with a clear message when no {@code --node-id} / {@code CORTEX_NODE_ID} is set
	 */
	protected void requireNodeId() {
		if (parent.hasNodeId()) {
			return;
		}
		throw new ParameterException(spec.commandLine(),
			"No worker id configured. Provide --node-id <id> (or set the CORTEX_NODE_ID environment variable) "
				+ "before starting the Cortex server. The id must be unique per worker and stable across restarts: "
				+ "Loom keys registration, node-kind restrictions and run attribution on it, and rejects a second "
				+ "worker that announces an id already in use.");
	}

	@Override
	public String getHostname() {
		return parent.getHostname();
	}

	@Override
	public int getPort() {
		return parent.getPort();
	}
}
