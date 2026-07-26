package io.metaloom.cli.cmd.pipeline;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.output.Table;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code pipeline} command group.
 */
@Singleton
@Command(name = "pipeline", description = "Inspect and run pipelines.",
	subcommands = {
		PipelineCommand.ListCommand.class,
		PipelineCommand.GetCommand.class,
		PipelineCommand.DeleteCommand.class,
		PipelineRunCommand.class
	})
public class PipelineCommand extends AbstractCliCommand {

	@Inject
	public PipelineCommand() {
	}

	@Override
	protected Integer execute() {
		return usage();
	}

	static Table pipelineTable(List<PipelineResponse> pipelines, io.metaloom.cli.output.Ansi ansi) {
		Table table = new Table("UUID", "NAME", "VERSION", "ENABLED", "PRIORITY");
		for (PipelineResponse pipeline : pipelines) {
			table.row(
				String.valueOf(pipeline.getUuid()),
				pipeline.getName(),
				String.valueOf(pipeline.getVersionNumber()),
				Boolean.TRUE.equals(pipeline.isEnabled()) ? ansi.green("yes") : ansi.dim("no"),
				String.valueOf(pipeline.getPriority()));
		}
		return table;
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List pipelines.")
	public static class ListCommand extends AbstractCliCommand {

		@Inject
		public ListCommand() {
		}

		@Override
		protected Integer execute() {
			List<PipelineResponse> pipelines = api().listPipelines();
			printer().printList(pipelines,
				items -> pipelineTable(items, printer().ansi()),
				pipeline -> String.valueOf(pipeline.getUuid()));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "get", description = "Show one pipeline.")
	public static class GetCommand extends AbstractCliCommand {

		@Parameters(index = "0", paramLabel = "PIPELINE", description = "Pipeline name or UUID.")
		String pipeline;

		@Option(names = "--definition", description = "Print only the pipeline definition JSON.")
		boolean definitionOnly;

		@Inject
		public GetCommand() {
		}

		@Override
		protected Integer execute() {
			PipelineResponse loaded = api().resolvePipeline(pipeline);
			if (definitionOnly) {
				// Deliberately raw on stdout: this is meant to be redirected into a file and
				// fed back through `pipeline create -f`.
				printer().out().println(loaded.getDefinition() == null ? "{}"
					: loaded.getDefinition().encodePrettily());
				printer().out().flush();
				return ExitCode.OK;
			}
			printer().printOne(loaded,
				p -> pipelineTable(List.of(p), printer().ansi()),
				p -> String.valueOf(p.getUuid()));
			return ExitCode.OK;
		}
	}

	@Singleton
	@Command(name = "delete", aliases = "rm", description = "Delete a pipeline and all of its versions.")
	public static class DeleteCommand extends AbstractCliCommand {

		@Parameters(index = "0", paramLabel = "PIPELINE", description = "Pipeline name or UUID.")
		String pipeline;

		@Option(names = { "-y", "--yes" }, description = "Do not ask for confirmation.")
		boolean assumeYes;

		@Inject
		public DeleteCommand() {
		}

		@Override
		protected Integer execute() {
			PipelineResponse loaded = api().resolvePipeline(pipeline);
			UUID uuid = loaded.getUuid();

			if (!assumeYes && !confirm(loaded.getName())) {
				printer().info("Aborted.");
				return ExitCode.OK;
			}

			api().deletePipeline(uuid);
			printer().printMessage("Deleted pipeline '" + loaded.getName() + "' (" + uuid + ").");
			return ExitCode.OK;
		}

		private boolean confirm(String name) {
			if (System.console() == null) {
				// Non-interactive and no --yes: refuse rather than guess. A destructive
				// default in a script is how people lose data.
				throw new io.metaloom.cli.client.CliException(ExitCode.USAGE,
					"Refusing to delete '" + name + "' without confirmation. Pass --yes.");
			}
			String answer = System.console().readLine("Delete pipeline '%s' and all its versions? [y/N] ", name);
			return answer != null && answer.trim().equalsIgnoreCase("y");
		}
	}
}
