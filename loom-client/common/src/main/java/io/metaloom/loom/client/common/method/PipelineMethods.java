package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineBreakpointRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineBreakpointResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunStatsResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionRestoreRequest;

public interface PipelineMethods {

	LoomClientRequest<PipelineResponse> loadPipeline(UUID uuid);

	LoomClientRequest<PipelineResponse> createPipeline(PipelineCreateRequest request);

	LoomClientRequest<PipelineResponse> updatePipeline(UUID uuid, PipelineUpdateRequest request);

	LoomClientRequest<PipelineListResponse> listPipelines();

	LoomClientRequest<PipelineRunListResponse> listPipelineRuns(UUID pipelineUuid);

	LoomClientRequest<PipelineRunStatsResponse> loadPipelineRunStats();

	LoomClientRequest<PipelineRunRecord> loadPipelineRun(UUID pipelineUuid, UUID runUuid);

	LoomClientRequest<PipelineRunItemListResponse> listPipelineRunItems(UUID pipelineUuid, UUID runUuid);

	/**
	 * Load the node executions of a single run item, including the outputs each node emitted.
	 *
	 * <p>
	 * The finest granularity the run engine records: one entry per graph node, and one per element for a node downstream of a {@code MANY} output. This is
	 * what answers "which node failed and what did it produce"; the run and item records only carry a coarse state.
	 * </p>
	 */
	LoomClientRequest<PipelineNodeTaskListResponse> listPipelineRunItemTasks(UUID pipelineUuid, UUID runUuid, UUID itemUuid);

	LoomClientRequest<NoResponse> deletePipeline(UUID uuid);

	// RUN LIFECYCLE

	/**
	 * Trigger a run of the pipeline. The server replies 202 once a processor has accepted
	 * the run, or 503 when no registered processor can serve it.
	 */
	LoomClientRequest<PipelineRunResponse> runPipeline(UUID pipelineUuid, PipelineRunRequest request);

	/**
	 * Suspend an in-flight run. Dispatch of new node tasks stops and the source scan is
	 * throttled; work already handed to a worker settles normally.
	 */
	LoomClientRequest<GenericMessageResponse> pausePipelineRun(UUID pipelineUuid, UUID runUuid);

	/** Resume a previously paused run. Fails with 409 if the run is not live any more. */
	LoomClientRequest<GenericMessageResponse> resumePipelineRun(UUID pipelineUuid, UUID runUuid);

	/**
	 * Cancel an in-flight run. In-flight worker tasks cannot be recalled, so they settle
	 * and their late results are ignored.
	 */
	LoomClientRequest<GenericMessageResponse> cancelPipelineRun(UUID pipelineUuid, UUID runUuid);

	// BREAKPOINTS
	//
	// Run state, not definition state: a breakpoint is set on a run that is already going and is
	// never written back into the stored pipeline.

	/** Load the nodes a run halts at, and the executions it is currently holding. */
	LoomClientRequest<PipelineBreakpointResponse> loadPipelineRunBreakpoints(UUID pipelineUuid, UUID runUuid);

	/**
	 * Replace the set of nodes a run halts at.
	 *
	 * <p>A whole-set replacement rather than an add/remove pair, so sending the same request twice
	 * leaves the run in the same state. An empty list disarms everything.</p>
	 */
	LoomClientRequest<PipelineBreakpointResponse> setPipelineRunBreakpoints(UUID pipelineUuid, UUID runUuid,
		PipelineBreakpointRequest request);

	/**
	 * Release every execution one node is holding. The breakpoint stays armed, so the next item
	 * reaching that node stops too.
	 */
	LoomClientRequest<GenericMessageResponse> continuePipelineRunBreakpoint(UUID pipelineUuid, UUID runUuid, String nodeId);

	/** Release exactly one held execution. Fails with 409 when the run is not holding anything. */
	LoomClientRequest<PipelineBreakpointResponse> stepPipelineRun(UUID pipelineUuid, UUID runUuid);

	// VERSIONS

	LoomClientRequest<PipelineVersionListResponse> listPipelineVersions(UUID pipelineUuid);

	LoomClientRequest<PipelineResponse> loadPipelineVersion(UUID pipelineUuid, int version);

	/** Restore an older version. This copies it forward as a new version rather than rewinding. */
	LoomClientRequest<PipelineResponse> restorePipelineVersion(UUID pipelineUuid, int version,
		PipelineVersionRestoreRequest request);
}
