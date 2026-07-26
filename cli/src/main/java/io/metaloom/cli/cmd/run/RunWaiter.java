package io.metaloom.cli.cmd.run;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.client.LoomApi;
import io.metaloom.cli.output.Printer;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;

/**
 * Blocks until a run reaches a terminal state.
 *
 * <p>Polling rather than listening: the events WebSocket is the nicer signal but it carries
 * no history, so a {@code --wait} that reconnects after a blip would hang forever. Re-reading
 * the row is dull and correct.</p>
 */
@Singleton
public class RunWaiter {

	/** Statuses from which a run will not move again. */
	private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "PARTIAL", "CANCELLED");

	private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

	@Inject
	public RunWaiter() {
	}

	public static boolean isTerminal(String status) {
		return status != null && TERMINAL.contains(status.toUpperCase());
	}

	/**
	 * The exit code a finished run should produce.
	 *
	 * <p>A run that finished FAILED or PARTIAL is a failure of the thing the user asked for,
	 * so it must not exit 0 - otherwise {@code metaloom pipeline run x --wait && deploy}
	 * deploys on a broken run.</p>
	 */
	public static int exitCodeFor(PipelineRunRecord run) {
		if (run == null) {
			return ExitCode.ERROR;
		}
		return "SUCCESS".equalsIgnoreCase(run.getStatus()) ? ExitCode.OK : ExitCode.RUN_NOT_SUCCESSFUL;
	}

	/**
	 * Poll until the run is terminal.
	 *
	 * @throws CliException {@link ExitCode#TIMEOUT} if the deadline passes first
	 */
	public PipelineRunRecord awaitTerminal(LoomApi api, Printer printer, UUID pipelineUuid, UUID runUuid,
		Duration timeout) {

		if (runUuid == null) {
			throw new CliException(ExitCode.SERVER_FAILURE, "The server returned no run id, so it cannot be awaited.");
		}
		long deadline = System.nanoTime() + timeout.toNanos();
		String lastStatus = null;

		while (true) {
			PipelineRunRecord run = api.loadRun(pipelineUuid, runUuid);
			String status = run.getStatus();

			if (!java.util.Objects.equals(status, lastStatus)) {
				printer.info("Run " + runUuid + " is " + printer.ansi().status(status) + ".");
				lastStatus = status;
			}
			if (isTerminal(status)) {
				printer.info("Run finished: " + printer.ansi().status(status)
					+ " (media=" + run.getMediaCount() + ", success=" + run.getSuccessCount()
					+ ", failed=" + run.getFailureCount() + ", skipped=" + run.getSkippedCount() + ").");
				if (run.getErrorMessage() != null && !run.getErrorMessage().isBlank()) {
					printer.error(run.getErrorMessage());
				}
				return run;
			}
			if (System.nanoTime() >= deadline) {
				throw new CliException(ExitCode.TIMEOUT,
					"Timed out waiting for run " + runUuid + "; it is still " + status + ".");
			}
			try {
				Thread.sleep(POLL_INTERVAL.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CliException(ExitCode.INTERRUPTED, "Interrupted while waiting for run " + runUuid + ".");
			}
		}
	}
}
