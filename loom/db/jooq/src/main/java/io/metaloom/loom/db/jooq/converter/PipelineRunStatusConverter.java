package io.metaloom.loom.db.jooq.converter;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;

/** Maps {@code pipeline_run.status} onto {@link PipelineRunStatus}. */
public class PipelineRunStatusConverter extends PipelineVocabularyConverter<PipelineRunStatus> {

	private static final long serialVersionUID = 1L;

	public PipelineRunStatusConverter() {
		super(PipelineRunStatus.class, "pipeline_run.status", PipelineRunStatus::parse);
	}
}
