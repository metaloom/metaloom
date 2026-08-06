package io.metaloom.cli.cmd.run;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.PipelineEventStream;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.config.CliConfigLoader;
import io.metaloom.cli.output.Ansi;
import io.metaloom.cli.output.Table;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code run} command group: inspect and control pipeline runs.
 */
@Singleton
@Command(name = "run", description = "Inspect and control pipeline runs.",
	subcommands = {
		RunCommand.ListCommand.class,
		RunCommand.GetCommand.class,
		RunCommand.ItemsCommand.class,
		RunCommand.FollowCommand.class,
		RunCommand.PauseCommand.class,
		RunCommand.ResumeCommand.class,
		RunCommand.CancelCommand.class,
		RunCommand.StatsCommand.class
	})
public class RunCommand extends AbstractCliCommand {

	@Inject
	public RunCommand() {
	}

	@Override
	protected Integer execute() {
		return usage();
	}

	static Table runTable(List<PipelineRunRecord> runs, Ansi ansi) {
		Table table = new Table("UUID", "STATUS", "STARTED", "MEDIA", "OK", "FAILED", "SKIPPED", "DURATION");
		for (PipelineRunRecord run : runs) {
			table.row(
				String.valueOf(run.getUuid()),
				ansi.status(name(run.getStatus())),
				run.getStarted() == null ? "" : run.getStarted(),
				String.valueOf(run.getMediaCount()),
				String.valueOf(run.getSuccessCount()),
				run.getFailureCount() > 0 ? ansi.red(String.valueOf(run.getFailureCount())) : "0",
				String.valueOf(run.getSkippedCount()),
				run.getDurationMs() == null ? "" : run.getDurationMs() + "ms");
		}
		return table;
	}

	/** Shared {@code --pipeline} option for the subcommands that take a run id. */
	abstract static class AbstractRunSubcommand extends AbstractCliCommand {

		@Parameters(index = "0", paramLabel = "RUN", description = "Run UUID.")
		UUID runUuid;

		@Option(names = { "-p", "--pipeline" }, paramLabel = "PIPELINE",
			description = "Pipeline name or UUID that owns the run. Faster than letting the CLI search for it.")
		String pipeline;

		@Inject
		RunLocator locator;

		protected RunLocator.Located locate() {
			return locator.locate(api(), pipeline, runUuid);
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List runs of a pipeline.")
	public static class ListCommand extends AbstractCliCommand {

		@Option(names = { "-p", "--pipeline" }, paramLabel = "PIPELINE", required = true,
			description = "Pipeline name or UUID.")
		String pipeline;

		@Option(names = "--status", paramLabel = "STATUS",
			description = "Only show runs in this status, e.g. FAILED.")
		String status;

		@Inject
		public ListCommand() {
		}

		@Override
		protected Integer execute() {
			PipelineResponse target = api().resolvePipeline(pipeline);
			List<PipelineRunRecord> runs = api().listRuns(target.getUuid());
			if (status != null && !status.isBlank()) {
				runs = runs.stream().filter(r -> status.equalsIgnoreCase(name(r.getStatus()))).toList();
			}
			printer().printList(runs,
				items -> runTable(items, printer().ansi()),
				run -> String.valueOf(run.getUuid()));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "get", description = "Show one run.")
	public static class GetCommand extends AbstractRunSubcommand {

		@Inject
		public GetCommand() {
		}

		@Override
		protected Integer execute() {
			PipelineRunRecord run = locate().run();
			printer().printOne(run,
				r -> runTable(List.of(r), printer().ansi()),
				r -> String.valueOf(r.getUuid()));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "items", description = "List the media items a run processed.")
	public static class ItemsCommand extends AbstractRunSubcommand {

		@Option(names = "--state", paramLabel = "STATE",
			description = "Only show items in this state, e.g. FAILED.")
		String state;

		@Inject
		public ItemsCommand() {
		}

		@Override
		protected Integer execute() {
			RunLocator.Located located = locate();
			List<PipelineRunItemRecord> items = api().listRunItems(located.pipelineUuid(), runUuid);
			if (state != null && !state.isBlank()) {
				items = items.stream().filter(i -> state.equalsIgnoreCase(name(i.getState()))).toList();
			}
			printer().printList(items,
				list -> {
					Table table = new Table("SEQ", "STATE", "PATH", "ERROR");
					for (PipelineRunItemRecord item : list) {
						table.row(
							String.valueOf(item.getItemSeq()),
							printer().ansi().status(name(item.getState())),
							item.getMediaPath(),
							item.getErrorMessage() == null ? "" : item.getErrorMessage());
					}
					return table;
				},
				item -> String.valueOf(item.getUuid()));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "follow", description = "Stream events for a run until it finishes.")
	public static class FollowCommand extends AbstractRunSubcommand {

		@Option(names = "--wait-timeout", paramLabel = "DURATION", defaultValue = "1h",
			description = "Stop following after this long.")
		String waitTimeout;

		private final PipelineEventStream events;

		@Inject
		public FollowCommand(PipelineEventStream events) {
			this.events = events;
		}

		@Override
		protected Integer execute() {
			RunLocator.Located located = locate();
			if (RunWaiter.isTerminal(located.run().getStatus())) {
				// The stream has no history, so following a finished run would just hang.
				printer().warn("Run " + runUuid + " already finished (" + name(located.run().getStatus())
					+ "). There is nothing left to stream.");
				return RunWaiter.exitCodeFor(located.run());
			}

			Duration timeout = CliConfigLoader.parseDuration(waitTimeout);
			// The run id is known up front here, so the server can do the filtering.
			try (PipelineEventStream.Subscription subscription = events.subscribe(null, runUuid, printer())) {
				subscription.watch(runUuid);
				if (!subscription.awaitCompletion(timeout)) {
					printer().warn("Stopped following after " + waitTimeout + "; the run may still be going.");
					return ExitCode.TIMEOUT;
				}
			}
			return RunWaiter.exitCodeFor(api().loadRun(located.pipelineUuid(), runUuid));
		}
	}

	@Singleton
	@Command(name = "pause", description = "Suspend an in-flight run.")
	public static class PauseCommand extends AbstractRunSubcommand {

		@Inject
		public PauseCommand() {
		}

		@Override
		protected Integer execute() {
			RunLocator.Located located = locate();
			printer().printMessage(api().pauseRun(located.pipelineUuid(), runUuid));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "resume", aliases = "start", description = "Resume a suspended run.")
	public static class ResumeCommand extends AbstractRunSubcommand {

		@Inject
		public ResumeCommand() {
		}

		@Override
		protected Integer execute() {
			RunLocator.Located located = locate();
			printer().printMessage(api().resumeRun(located.pipelineUuid(), runUuid));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "cancel", aliases = "stop", description = "Cancel an in-flight run.")
	public static class CancelCommand extends AbstractRunSubcommand {

		@Inject
		public CancelCommand() {
		}

		@Override
		protected Integer execute() {
			RunLocator.Located located = locate();
			printer().printMessage(api().cancelRun(located.pipelineUuid(), runUuid));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "stats", description = "Show daily run statistics across all pipelines.")
	public static class StatsCommand extends AbstractCliCommand {

		@Inject
		public StatsCommand() {
		}

		@Override
		protected Integer execute() {
			var stats = api().runStats();
			var daily = stats.getDaily() == null ? List.<io.metaloom.loom.rest.model.pipeline.PipelineRunDayStatsRecord>of()
				: stats.getDaily();
			printer().printList(daily,
				list -> {
					Table table = new Table("DATE", "RUNS", "OK", "FAILED", "SKIPPED");
					for (var day : list) {
						table.row(
							day.getDate(),
							String.valueOf(day.getRunCount()),
							String.valueOf(day.getSuccessCount()),
							day.getFailureCount() > 0
								? printer().ansi().red(String.valueOf(day.getFailureCount()))
								: "0",
							String.valueOf(day.getSkippedCount()));
					}
					return table;
				},
				day -> day.getDate());
			return ExitCode.OK;
		}
	}
	/**
	 * The token the tables and filters work with. Kept here rather than inlined as
	 * {@code .name()} because the server may leave a status unset, and a CLI table cell must
	 * not be the string "null".
	 */
	private static String name(Enum<?> value) {
		return value == null ? "" : value.name();
	}

}
