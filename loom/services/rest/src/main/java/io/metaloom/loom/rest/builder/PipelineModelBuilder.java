package io.metaloom.loom.rest.builder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDayStats;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunDayStatsRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunStatsResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionListResponse;

/**
 * Builds the flattened pipeline REST models.
 *
 * <p>
 * Persistence keeps {@link Pipeline} and {@link PipelineVersion} as two separate elements. The REST API exposes a single merged {@link PipelineResponse}, so
 * every builder here takes both halves and folds them together.
 * </p>
 */
public interface PipelineModelBuilder extends ModelBuilder, UserModelBuilder {

	/**
	 * Merge a pipeline with the version it should be rendered from.
	 *
	 * @param pipeline
	 *            the pipeline; supplies the uuid and the creator/editor status
	 * @param version
	 *            the version to render; may be {@code null} for a pipeline that has no version yet
	 */
	default PipelineResponse toResponse(Pipeline pipeline, PipelineVersion version) {
		PipelineResponse response = new PipelineResponse();
		response.setUuid(pipeline.getUuid());
		response.setMeta(pipeline.getMeta());
		applyVersion(response, version);
		setStatus(pipeline, response);
		return response;
	}

	/**
	 * Render a single historic version of a pipeline. The creator/editor status is taken from the version, since that is who authored this revision.
	 */
	default PipelineResponse toVersionResponse(UUID pipelineUuid, PipelineVersion version) {
		PipelineResponse response = new PipelineResponse();
		response.setUuid(pipelineUuid);
		response.setMeta(version.getMeta());
		applyVersion(response, version);
		setStatus(version, response);
		return response;
	}

	private void applyVersion(PipelineResponse response, PipelineVersion version) {
		if (version == null) {
			return;
		}
		response.setVersionUuid(version.getUuid());
		response.setVersionNumber(version.getVersionNumber());
		response.setName(version.getName());
		response.setDescription(version.getDescription());
		response.setDefinition(version.getDefinition());
		response.setEnabled(version.isEnabled());
		response.setPriority(version.getPriority());
		response.setDryRun(version.isDryRun());
	}

	/**
	 * @param versions
	 *            latest version per pipeline uuid; entries may be missing for pipelines without a version
	 */
	default PipelineListResponse toPipelineList(Page<Pipeline> page, Map<UUID, PipelineVersion> versions) {
		return setPage(new PipelineListResponse(), page, pipeline -> toResponse(pipeline, versions.get(pipeline.getUuid())));
	}

	default PipelineVersionListResponse toPipelineVersionList(UUID pipelineUuid, Page<PipelineVersion> page) {
		return setPage(new PipelineVersionListResponse(), page, version -> toVersionResponse(pipelineUuid, version));
	}

	default PipelineRunRecord toPipelineRunRecord(PipelineRun run) {
		PipelineRunRecord record = new PipelineRunRecord();
		record.setUuid(run.getUuid());
		record.setPipelineUuid(run.getPipelineUuid());
		record.setPipelineVersion(run.getPipelineVersion());
		record.setStarted(run.getStarted() != null ? run.getStarted().toString() : null);
		record.setFinished(run.getFinished() != null ? run.getFinished().toString() : null);
		record.setStatus(run.getStatus());
		record.setMediaCount(run.getMediaCount());
		record.setSuccessCount(run.getSuccessCount());
		record.setFailureCount(run.getFailureCount());
		record.setSkippedCount(run.getSkippedCount());
		record.setDryRun(run.isDryRun());
		record.setErrorMessage(run.getErrorMessage());
		record.setDurationMs(run.getDurationMs());
		record.setMeta(run.getMeta());
		return record;
	}

	default PipelineRunListResponse toPipelineRunList(Page<PipelineRun> page) {
		return setPage(new PipelineRunListResponse(), page, this::toPipelineRunRecord);
	}

	/**
	 * Render daily run stats as a zero-filled window of {@code days} buckets ending at {@code today}, oldest first.
	 */
	default PipelineRunStatsResponse toPipelineRunStats(List<PipelineRunDayStats> stats, LocalDate today, int days) {
		Map<LocalDate, PipelineRunDayStats> byDate = stats.stream()
			.collect(Collectors.toMap(PipelineRunDayStats::getDate, Function.identity()));
		PipelineRunStatsResponse response = new PipelineRunStatsResponse();
		for (int i = days - 1; i >= 0; i--) {
			LocalDate date = today.minusDays(i);
			PipelineRunDayStats day = byDate.get(date);
			PipelineRunDayStatsRecord record = new PipelineRunDayStatsRecord().setDate(date.toString());
			if (day != null) {
				record.setRunCount(day.getRunCount());
				record.setSuccessCount(day.getSuccessCount());
				record.setFailureCount(day.getFailureCount());
				record.setSkippedCount(day.getSkippedCount());
			}
			response.add(record);
		}
		return response;
	}

	default PipelineRunItemRecord toPipelineRunItemRecord(PipelineRunItem item) {
		PipelineRunItemRecord record = new PipelineRunItemRecord();
		record.setUuid(item.getUuid());
		record.setRunUuid(item.getRunUuid());
		record.setItemSeq(item.getItemSeq());
		record.setMediaPath(item.getMediaPath());
		record.setSha512(item.getSha512());
		record.setSizeBytes(item.getSizeBytes());
		record.setState(item.getState());
		record.setErrorMessage(item.getErrorMessage());
		return record;
	}

	default PipelineRunItemListResponse toPipelineRunItemList(Page<PipelineRunItem> page) {
		return setPage(new PipelineRunItemListResponse(), page, this::toPipelineRunItemRecord);
	}

}
