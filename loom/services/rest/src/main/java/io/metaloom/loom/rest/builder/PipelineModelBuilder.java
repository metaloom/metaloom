package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;

public interface PipelineModelBuilder extends ModelBuilder, UserModelBuilder {

	default PipelineResponse toResponse(Pipeline pipeline) {
		PipelineResponse response = new PipelineResponse();
		response.setUuid(pipeline.getUuid());
		response.setName(pipeline.getName());
		response.setDescription(pipeline.getDescription());
		response.setDefinition(pipeline.getDefinition());
		response.setEnabled(pipeline.isEnabled());
		response.setPriority(pipeline.getPriority());
		response.setDryRun(pipeline.isDryRun());
		setStatus(pipeline, response);
		return response;
	}

	default PipelineListResponse toPipelineList(Page<Pipeline> page) {
		return setPage(new PipelineListResponse(), page, this::toResponse);
	}

	default PipelineRunRecord toPipelineRunRecord(PipelineRun run) {
		PipelineRunRecord record = new PipelineRunRecord();
		record.setUuid(run.getUuid());
		record.setPipelineUuid(run.getPipelineUuid());
		record.setPipelineVersion(run.getPipelineVersion());
		record.setStarted(run.getStarted() != null ? run.getStarted().toString() : null);
		record.setFinished(run.getFinished() != null ? run.getFinished().toString() : null);
		record.setStatus(run.getStatus());
		record.setMediaCount(run.getMediaCount());
		record.setSuccessCount(run.getSuccessCount());
		record.setFailureCount(run.getFailureCount());
		record.setSkippedCount(run.getSkippedCount());
		record.setDryRun(run.isDryRun());
		record.setErrorMessage(run.getErrorMessage());
		record.setDurationMs(run.getDurationMs());
		record.setMeta(run.getMeta());
		return record;
	}

	default PipelineRunListResponse toPipelineRunList(Page<PipelineRun> page) {
		return setPage(new PipelineRunListResponse(), page, this::toPipelineRunRecord);
	}

}
