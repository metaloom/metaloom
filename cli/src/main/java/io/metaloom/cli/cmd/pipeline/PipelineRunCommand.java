package io.metaloom.cli.cmd.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.client.PipelineEventStream;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.cmd.run.RunWaiter;
import io.metaloom.cli.config.CliConfigLoader;
import io.metaloom.cli.output.Table;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Trigger a pipeline run, optionally narrowed to a folder and optionally watched.
 */
@Singleton
@Command(name = "run", description = "Trigger a pipeline run.")
public class PipelineRunCommand extends AbstractCliCommand {

	@Parameters(index = "0", paramLabel = "PIPELINE", description = "Pipeline name or UUID.")
	String pipeline;

	@Option(names = { "-d", "--dir" }, paramLabel = "PATH",
		description = "Run against this directory or file. Uses the source node's differential "
			+ "index-backed scan, so unchanged media is skipped.")
	String dir;

	@Option(names = { "-g", "--glob" }, paramLabel = "GLOB",
		description = "Run against media matching this glob. Repeatable. Forces a full re-walk "
			+ "and takes precedence over --dir.")
	List<String> globs;

	@Option(names = { "-a", "--asset" }, paramLabel = "UUID",
		description = "Run against these assets. Repeatable. Takes precedence over --dir and --glob.")
	List<UUID> assets;

	@Option(names = "--dry-run", description = "Override the pipeline's dry-run flag for this run.")
	Boolean dryRun;

	@Option(names = { "-f", "--follow" }, description = "Stream run events until the run finishes.")
	boolean follow;

	@Option(names = "--wait", description = "Block until the run finishes. Exits 21 if it did not succeed.")
	boolean wait;

	@Option(names = "--wait-timeout", paramLabel = "DURATION", defaultValue = "1h",
		description = "Give up waiting after this long. Exits 124.")
	String waitTimeout;

	private final PipelineEventStream events;
	private final RunWaiter waiter;

	@Inject
	public PipelineRunCommand(PipelineEventStream events, RunWaiter waiter) {
		this.events = events;
		this.waiter = waiter;
	}

	@Override
	protected Integer execute() {
		PipelineResponse target = api().resolvePipeline(pipeline);
		UUID pipelineUuid = target.getUuid();
		PipelineRunRequest request = buildRequest();
		Duration timeout = CliConfigLoader.parseDuration(waitTimeout);

		if (follow) {
			return runAndFollow(target, pipelineUuid, request, timeout);
		}

		PipelineRunResponse response = api().runPipeline(pipelineUuid, request);
		reportDispatch(response);

		if (wait) {
			PipelineRunRecord finished = waiter.awaitTerminal(api(), printer(), pipelineUuid,
				response.getRunUuid(), timeout);
			return RunWaiter.exitCodeFor(finished);
		}
		return ExitCode.OK;
	}

	/**
	 * Subscribe first, then dispatch.
	 *
	 * <p>The events WebSocket carries no history: it only forwards what happens after the
	 * socket is open. Posting the run first and connecting afterwards therefore drops
	 * {@code PIPELINE_STARTED} and any node events that land in the gap. Connecting first
	 * costs nothing and makes the output complete.</p>
	 *
	 * <p>The run UUID is not known until the POST returns, so the stream buffers what it sees
	 * and replays it through the filter once told which run to watch.</p>
	 */
	private Integer runAndFollow(PipelineResponse target, UUID pipelineUuid, PipelineRunRequest request,
		Duration timeout) {

		try (PipelineEventStream.Subscription subscription = events.subscribe(target.getName(), printer())) {
			PipelineRunResponse response = api().runPipeline(pipelineUuid, request);
			reportDispatch(response);

			UUID runUuid = response.getRunUuid();
			if (runUuid == null) {
				throw new CliException(ExitCode.SERVER_FAILURE,
					"The server accepted the request but returned no run id, so it cannot be followed.");
			}
			subscription.watch(runUuid);

			boolean completed = subscription.awaitCompletion(timeout);
			if (!completed) {
				printer().warn("Stopped following after " + waitTimeout + "; the run may still be going.");
				return ExitCode.TIMEOUT;
			}

			// The event stream says the run ended; the row says how. Ask, so the exit code
			// reflects SUCCESS vs PARTIAL vs FAILED rather than merely "it stopped".
			PipelineRunRecord finished = api().loadRun(pipelineUuid, runUuid);
			return RunWaiter.exitCodeFor(finished);
		}
	}

	private void reportDispatch(PipelineRunResponse response) {
		if (!response.isDispatched()) {
			throw new CliException(ExitCode.SERVER_FAILURE,
				response.getMessage() == null ? "The run was not dispatched." : response.getMessage());
		}
		if (printer().isQuiet()) {
			printer().out().println(response.getRunUuid());
			printer().out().flush();
			return;
		}
		if (printer().format() != io.metaloom.cli.output.OutputFormat.TABLE) {
			printer().printOne(response,
				r -> new Table("RUN", "PROCESSOR").row(String.valueOf(r.getRunUuid()), r.getProcessorNodeId()),
				r -> String.valueOf(r.getRunUuid()));
			return;
		}
		printer().info("Run " + response.getRunUuid() + " dispatched to " + response.getProcessorNodeId() + ".");
	}

	private PipelineRunRequest buildRequest() {
		PipelineRunRequest request = new PipelineRunRequest();
		if (dir != null && !dir.isBlank()) {
			// Sent as an absolute path: it is resolved on the worker, and a relative path
			// would silently mean something different there.
			request.setPath(Path.of(dir).toAbsolutePath().normalize().toString());
		}
		if (globs != null && !globs.isEmpty()) {
			request.setPathGlobs(new ArrayList<>(globs));
			if (dir != null) {
				printer().warn("--glob takes precedence over --dir; --dir is ignored for this run.");
			}
		}
		if (assets != null && !assets.isEmpty()) {
			request.setMediaUuids(new ArrayList<>(assets));
		}
		if (dryRun != null) {
			request.setDryRun(dryRun);
		}
		return request;
	}
}
