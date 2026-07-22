package io.metaloom.loom.rest.model.pipeline;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Aggregated pipeline run statistics across all pipelines, bucketed per calendar day.
 */
public class PipelineRunStatsResponse implements RestResponseModel<PipelineRunStatsResponse> {

	@JsonPropertyDescription("Daily run stats buckets, oldest first. Days without runs are zero-filled.")
	private List<PipelineRunDayStatsRecord> daily = new ArrayList<>();

	public PipelineRunStatsResponse() {
	}

	public List<PipelineRunDayStatsRecord> getDaily() {
		return daily;
	}

	public PipelineRunStatsResponse setDaily(List<PipelineRunDayStatsRecord> daily) {
		this.daily = daily;
		return this;
	}

	public PipelineRunStatsResponse add(PipelineRunDayStatsRecord record) {
		this.daily.add(record);
		return this;
	}

	@Override
	public PipelineRunStatsResponse self() {
		return this;
	}

}
