package io.metaloom.loom.db.jooq.converter;

import io.metaloom.loom.api.pipeline.RunItemState;

/** Maps {@code pipeline_run_item.state} onto {@link RunItemState}. */
public class RunItemStateConverter extends PipelineVocabularyConverter<RunItemState> {

	private static final long serialVersionUID = 1L;

	public RunItemStateConverter() {
		super(RunItemState.class, "pipeline_run_item.state", RunItemState::parse);
	}
}
