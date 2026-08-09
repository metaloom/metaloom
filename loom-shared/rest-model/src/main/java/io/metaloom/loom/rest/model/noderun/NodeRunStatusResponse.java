package io.metaloom.loom.rest.model.noderun;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.vertx.core.json.JsonObject;

/**
 * Status and results of an ad-hoc node run.
 *
 * <p>
 * This is what a caller polls after {@link NodeRunResponse} handed it a uuid, and it is the only view
 * an ad-hoc run has: these runs are scoped to their creator and deliberately do not appear under
 * {@code /api/v1/pipelines/:uuid/runs} or in the pipeline run statistics, which describe scheduled
 * processing.
 * </p>
 */
public class NodeRunStatusResponse implements RestResponseModel<NodeRunStatusResponse> {

	@JsonPropertyDescription("Identifier of the run.")
	private UUID uuid;

	@JsonPropertyDescription("Current status: RUNNING, PAUSED, SUCCESS, PARTIAL, FAILED or CANCELLED.")
	private String status;

	@JsonPropertyDescription("How many items the run was given.")
	private int mediaCount;

	@JsonPropertyDescription("How many items completed with every node succeeding.")
	private int successCount;

	@JsonPropertyDescription("How many items had a node fail.")
	private int failureCount;

	@JsonPropertyDescription("How many items were skipped.")
	private int skippedCount;

	@JsonPropertyDescription("When the run started, as an ISO-8601 instant.")
	private String started;

	@JsonPropertyDescription("When the run finished, as an ISO-8601 instant. Null while it is still going.")
	private String finished;

	@JsonPropertyDescription("Total wall clock of the run in milliseconds, once it has finished.")
	private Long durationMs;

	@JsonPropertyDescription("Why the run failed, when it did.")
	private String errorMessage;

	@JsonPropertyDescription("The graph this run was started with, exactly as submitted.")
	private JsonObject definition;

	@JsonPropertyDescription("Per-item node results. Present only when the caller asked for them.")
	private List<NodeRunItemResult> results;

	public NodeRunStatusResponse() {
	}

	public UUID getUuid() {
		return uuid;
	}

	public NodeRunStatusResponse setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

	public String getStatus() {
		return status;
	}

	public NodeRunStatusResponse setStatus(String status) {
		this.status = status;
		return this;
	}

	public int getMediaCount() {
		return mediaCount;
	}

	public NodeRunStatusResponse setMediaCount(int mediaCount) {
		this.mediaCount = mediaCount;
		return this;
	}

	public int getSuccessCount() {
		return successCount;
	}

	public NodeRunStatusResponse setSuccessCount(int successCount) {
		this.successCount = successCount;
		return this;
	}

	public int getFailureCount() {
		return failureCount;
	}

	public NodeRunStatusResponse setFailureCount(int failureCount) {
		this.failureCount = failureCount;
		return this;
	}

	public int getSkippedCount() {
		return skippedCount;
	}

	public NodeRunStatusResponse setSkippedCount(int skippedCount) {
		this.skippedCount = skippedCount;
		return this;
	}

	public String getStarted() {
		return started;
	}

	public NodeRunStatusResponse setStarted(String started) {
		this.started = started;
		return this;
	}

	public String getFinished() {
		return finished;
	}

	public NodeRunStatusResponse setFinished(String finished) {
		this.finished = finished;
		return this;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public NodeRunStatusResponse setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
		return this;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public NodeRunStatusResponse setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
		return this;
	}

	public JsonObject getDefinition() {
		return definition;
	}

	public NodeRunStatusResponse setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}

	public List<NodeRunItemResult> getResults() {
		return results;
	}

	public NodeRunStatusResponse setResults(List<NodeRunItemResult> results) {
		this.results = results;
		return this;
	}

	@Override
	public NodeRunStatusResponse self() {
		return this;
	}
}
