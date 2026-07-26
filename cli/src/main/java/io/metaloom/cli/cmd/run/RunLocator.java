package io.metaloom.cli.cmd.run;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.client.CliException;
import io.metaloom.cli.client.LoomApi;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;

/**
 * Finds the pipeline a run belongs to.
 *
 * <p>The REST API nests runs under their pipeline ({@code /pipelines/:uuid/runs/:runUuid}),
 * but a person holding a run id from a log line or a colleague does not know the pipeline.
 * Requiring both would be an unhelpful piece of API plumbing leaking into the UX, so when
 * {@code --pipeline} is omitted this sweeps the pipelines to find the owner.</p>
 *
 * <p>That sweep is O(pipelines) requests, which is why {@code --pipeline} exists and is
 * always the faster path.</p>
 */
@Singleton
public class RunLocator {

	/** A run and the pipeline that owns it. */
	public record Located(UUID pipelineUuid, PipelineRunRecord run) {
	}

	@Inject
	public RunLocator() {
	}

	/**
	 * @param api          the client
	 * @param pipelineHint what {@code --pipeline} was given, may be null
	 * @param runUuid      the run
	 */
	public Located locate(LoomApi api, String pipelineHint, UUID runUuid) {
		if (pipelineHint != null && !pipelineHint.isBlank()) {
			PipelineResponse pipeline = api.resolvePipeline(pipelineHint);
			return new Located(pipeline.getUuid(), api.loadRun(pipeline.getUuid(), runUuid));
		}

		for (PipelineResponse pipeline : api.listPipelines()) {
			for (PipelineRunRecord run : api.listRuns(pipeline.getUuid())) {
				if (runUuid.equals(run.getUuid())) {
					return new Located(pipeline.getUuid(), run);
				}
			}
		}
		throw new CliException(ExitCode.NOT_FOUND,
			"No run " + runUuid + " found. If it is older than the most recent page of runs, "
				+ "pass --pipeline to look it up directly.");
	}
}
