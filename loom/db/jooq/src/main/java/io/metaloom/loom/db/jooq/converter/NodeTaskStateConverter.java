package io.metaloom.loom.db.jooq.converter;

import io.metaloom.loom.api.pipeline.NodeTaskState;

/** Maps {@code pipeline_node_task.state} onto {@link NodeTaskState}. */
public class NodeTaskStateConverter extends PipelineVocabularyConverter<NodeTaskState> {

	private static final long serialVersionUID = 1L;

	public NodeTaskStateConverter() {
		super(NodeTaskState.class, "pipeline_node_task.state", NodeTaskState::parse);
	}
}
