package io.metaloom.cortex.pipeline.api;

/**
 * State of a pipeline node execution for a given media item.
 */
public enum NodeState {

	PENDING,

	RUNNING,

	COMPLETED,

	FAILED,

	SKIPPED
}
