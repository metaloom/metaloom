package io.metaloom.loom.agent.sandbox.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.sandbox.error.SandboxScheduleException;
import io.metaloom.loom.api.options.SandboxOptions;

/**
 * Rootless-podman sandbox backend (local dev).
 *
 * <p>Runs a Session Runner as a hardened {@code podman} container and returns an endpoint reachable on
 * 127.0.0.1 via a published port. Mirrors the runtime hardening the kubernetes pod spec applies
 * (read-only rootfs, dropped caps, pids/mem/cpu limits, non-root, no-new-privileges). Kept behind the
 * same {@link SandboxBackend} interface as the kubernetes adapter so the orchestrator never knows
 * which one is active.</p>
 */
public class PodmanBackend implements SandboxBackend {

	private static final Logger log = LoggerFactory.getLogger(PodmanBackend.class);

	private final SandboxOptions options;

	public PodmanBackend(SandboxOptions options) {
		this.options = options;
	}

	@Override
	public SandboxInfo create(String session, String name, String token) {
		List<String> cmd = new ArrayList<>(List.of(
			"podman", "run", "-d",
			"--name", name,
			"--label", "loom-session=" + session,
			"--label", "loom-runner=1",
			"--read-only",
			"--tmpfs", "/workspace:rw,size=" + options.getWorkspaceSize(),
			"--tmpfs", "/tmp:rw,size=64m",
			"--cap-drop=ALL",
			"--security-opt", "no-new-privileges",
			"--pids-limit", "256",
			"--memory", options.getMemLimit(),
			"--cpus", options.getCpuLimit(),
			"-e", "RUNNER_TOKEN=" + token,
			"-e", "RUNNER_WORKSPACE=/workspace",
			"-e", "RUNNER_PORT=8080",
			"-p", "127.0.0.1::8080",
			options.getImage()));

		ExecResult run = exec(60, cmd);
		if (run.exitCode() != 0) {
			throw new SandboxScheduleException("podman run failed: " + firstNonBlank(run.stderr(), run.stdout()));
		}

		// Resolve the published host port for the runnerd container port 8080.
		ExecResult port = exec(30, List.of("podman", "port", name, "8080/tcp"));
		String hostPort = port.stdout().isBlank() ? "" : port.stdout().strip().lines().findFirst().orElse("").strip();
		if (hostPort.isBlank()) {
			delete(session, name);
			throw new SandboxScheduleException("podman: could not resolve published port for " + name);
		}
		// hostPort looks like "127.0.0.1:43217"
		int idx = hostPort.lastIndexOf(':');
		String portNumber = idx >= 0 ? hostPort.substring(idx + 1) : hostPort;
		String endpoint = "http://127.0.0.1:" + portNumber;
		log.info("podman sandbox created session={} name={} endpoint={}", session, name, endpoint);
		return new SandboxInfo(name, endpoint, System.currentTimeMillis());
	}

	@Override
	public void delete(String session, String podName) {
		exec(30, List.of("podman", "rm", "-f", podName));
	}

	@Override
	public SandboxStatus status(String podName) {
		ExecResult res = exec(30, List.of("podman", "inspect", "-f", "{{.State.Running}}", podName));
		if (res.exitCode() != 0 || res.stdout().isBlank()) {
			return new SandboxStatus("Gone", null, 0L);
		}
		boolean running = "true".equalsIgnoreCase(res.stdout().strip());
		return new SandboxStatus(running ? "Running" : "Stopped", null, 0L);
	}

	private static String firstNonBlank(String a, String b) {
		String sa = a == null ? "" : a.strip();
		return !sa.isBlank() ? sa : (b == null ? "" : b.strip());
	}

	private ExecResult exec(long timeoutSeconds, List<String> command) {
		try {
			Process process = new ProcessBuilder(command).start();
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new SandboxScheduleException("podman command timed out: " + String.join(" ", command));
			}
			return new ExecResult(process.exitValue(), stdout, stderr);
		} catch (IOException e) {
			throw new SandboxScheduleException("podman command failed: " + String.join(" ", command), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SandboxScheduleException("podman command interrupted: " + String.join(" ", command), e);
		}
	}

	private record ExecResult(int exitCode, String stdout, String stderr) {
	}
}
