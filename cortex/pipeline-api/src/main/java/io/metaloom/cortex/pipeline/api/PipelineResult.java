package io.metaloom.cortex.pipeline.api;

import java.util.List;
import java.util.Map;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Result of executing a full pipeline on a single media item.
 */
public class PipelineResult {

	private final String pipelineName;
	private final LoomMedia media;
	private final Map<String, NodeResult> nodeResults;
	private final long totalDurationMs;
	private final boolean dryRun;

	public PipelineResult(String pipelineName, LoomMedia media, Map<String, NodeResult> nodeResults,
			long totalDurationMs, boolean dryRun) {
		this.pipelineName = pipelineName;
		this.media = media;
		this.nodeResults = nodeResults;
		this.totalDurationMs = totalDurationMs;
		this.dryRun = dryRun;
	}

	public String getPipelineName() {
		return pipelineName;
	}

	public LoomMedia getMedia() {
		return media;
	}

	public Map<String, NodeResult> getNodeResults() {
		return nodeResults;
	}

	public long getTotalDurationMs() {
		return totalDurationMs;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public boolean isSuccess() {
		return nodeResults.values().stream()
				.allMatch(r -> r.getState() == NodeState.COMPLETED || r.getState() == NodeState.SKIPPED);
	}

	@Override
	public String toString() {
		return "PipelineResult{pipeline=" + pipelineName + ", success=" + isSuccess() + ", duration=" + totalDurationMs
				+ "ms, nodes=" + nodeResults.size() + (dryRun ? " [DRY-RUN]" : "") + "}";
	}
}
