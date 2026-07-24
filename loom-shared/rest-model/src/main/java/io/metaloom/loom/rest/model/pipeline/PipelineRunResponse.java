package io.metaloom.loom.rest.model.pipeline;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Response returned when a pipeline run has been dispatched.
 *
 * <p>The result reflects the outcome of <em>starting</em> the run — selecting a
 * processor and handing it the source node — not the outcome of the pipeline
 * itself. Progress and per-node status can be observed on the pipeline events
 * WebSocket (<code>/api/v1/pipelines/events/ws</code>), and the run can be polled
 * via <code>GET /api/v1/pipelines/:uuid/runs</code> using {@link #runUuid}.</p>
 */
public class PipelineRunResponse implements RestResponseModel<PipelineRunResponse> {

	@JsonPropertyDescription("Unique identifier of the started pipeline run.")
	private UUID runUuid;

	@JsonPropertyDescription("Node identifier of the processor that accepted the run.")
	private String processorNodeId;

	@JsonPropertyDescription("Whether the run was successfully dispatched to a processor.")
	private boolean dispatched;

	@JsonPropertyDescription("Human-readable status or error message.")
	private String message;

	public PipelineRunResponse() {
	}

	public UUID getRunUuid() {
		return runUuid;
	}

	public PipelineRunResponse setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public String getProcessorNodeId() {
		return processorNodeId;
	}

	public PipelineRunResponse setProcessorNodeId(String processorNodeId) {
		this.processorNodeId = processorNodeId;
		return this;
	}

	public boolean isDispatched() {
		return dispatched;
	}

	public PipelineRunResponse setDispatched(boolean dispatched) {
		this.dispatched = dispatched;
		return this;
	}

	public String getMessage() {
		return message;
	}

	public PipelineRunResponse setMessage(String message) {
		this.message = message;
		return this;
	}

	@Override
	public PipelineRunResponse self() {
		return this;
	}
}
