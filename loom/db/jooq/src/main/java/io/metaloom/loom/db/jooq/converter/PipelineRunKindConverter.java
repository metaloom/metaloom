package io.metaloom.loom.db.jooq.converter;

import io.metaloom.loom.api.pipeline.PipelineRunKind;

/** Maps {@code pipeline_run.kind} onto {@link PipelineRunKind}. */
public class PipelineRunKindConverter extends PipelineVocabularyConverter<PipelineRunKind> {

	private static final long serialVersionUID = 1L;

	public PipelineRunKindConverter() {
		super(PipelineRunKind.class, "pipeline_run.kind", PipelineRunKind::parse);
	}
}
