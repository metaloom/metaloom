package io.metaloom.loom.rest.builder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDayStats;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.page.Page;
import io.vertx.core.json.JsonObject;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeTaskRecord;
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

	default PipelineNodeTaskRecord toPipelineNodeTaskRecord(UUID pipelineUuid, PipelineNodeTask task) {
		PipelineNodeTaskRecord record = new PipelineNodeTaskRecord();
		record.setUuid(task.getUuid());
		record.setItemUuid(task.getItemUuid());
		record.setRunUuid(task.getRunUuid());
		record.setNodeId(task.getNodeId());
		record.setNodeKind(task.getNodeKind());
		record.setElementSeq(task.getElementSeq());
		record.setGeneration(task.getGeneration());
		record.setState(task.getState());
		record.setAttempt(task.getAttempt());
		record.setMaxAttempts(task.getMaxAttempts());
		record.setLeasedBy(task.getLeasedBy());
		record.setStarted(task.getStarted());
		record.setFinished(task.getFinished());
		record.setDurationMs(task.getDurationMs());
		record.setErrorMessage(task.getErrorMessage());
		// Passed through verbatim. The column already holds the {portId: PortPayload} shape the
		// client wants, so decoding it into typed models here and re-encoding it would only
		// create a second place for the wire format to drift.
		record.setOutputs(task.getOutputs());
		record.setPreviews(previewMetadata(pipelineUuid, task));
		return record;
	}

	/**
	 * Render the stored previews as metadata plus a fetch URL, dropping the bytes.
	 *
	 * <p>
	 * The bytes live in the same JSONB column, but sending them inline would put a base64 blob per
	 * image port into a JSON response the browser cannot cache per image and cannot render
	 * progressively. A URL per preview costs one extra request each and gets ETag handling for free.
	 * </p>
	 *
	 * <p>
	 * A skipped preview keeps its reason and gets no URL: "too large to preview" and "this port
	 * emitted nothing" mean opposite things and must not look alike.
	 * </p>
	 *
	 * @return the metadata, or null when this execution has no previews at all
	 */
	default JsonObject previewMetadata(UUID pipelineUuid, PipelineNodeTask task) {
		JsonObject stored = task.getPreviews();
		if (stored == null || stored.isEmpty()) {
			return null;
		}
		JsonObject rendered = new JsonObject();
		for (String portId : stored.fieldNames()) {
			JsonObject preview = stored.getJsonObject(portId);
			if (preview == null) {
				continue;
			}
			JsonObject entry = new JsonObject();
			if (preview.getString("data") != null) {
				entry
					.put("mimeType", preview.getString("mimeType"))
					.put("width", preview.getInteger("width", 0))
					.put("height", preview.getInteger("height", 0))
					.put("url", previewUrl(pipelineUuid, task, portId));
			} else {
				entry.put("skippedReason", preview.getString("skippedReason"));
			}
			rendered.put(portId, entry);
		}
		return rendered.isEmpty() ? null : rendered;
	}

	/**
	 * The path the bytes of one preview are served from.
	 *
	 * <p>
	 * Addressed through the run item that owns the task, so the ownership chain a caller must already
	 * satisfy to have seen this record is the same one that guards the bytes.
	 * </p>
	 */
	default String previewUrl(UUID pipelineUuid, PipelineNodeTask task, String portId) {
		return "/api/v1/pipelines/" + pipelineUuid + "/runs/" + task.getRunUuid()
			+ "/items/" + task.getItemUuid() + "/tasks/" + task.getUuid()
			+ "/previews/" + java.net.URLEncoder.encode(portId, java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Render every node execution of one run item.
	 *
	 * <p>
	 * Unpaged on purpose: the set is bounded by the graph (one row per node, plus one per element for a node downstream of a {@code MANY} output), and the
	 * drill-down needs all of it at once to paint per-node state onto the canvas. Splitting it across pages would mean the canvas could only ever show the
	 * first page of its own nodes.
	 * </p>
	 */
	default PipelineNodeTaskListResponse toPipelineNodeTaskList(UUID pipelineUuid, List<PipelineNodeTask> tasks) {
		PipelineNodeTaskListResponse response = new PipelineNodeTaskListResponse();
		tasks.stream().map(task -> toPipelineNodeTaskRecord(pipelineUuid, task)).forEach(response::add);
		return response;
	}

	/**
	 * Render what a run is halting at and what it is currently holding.
	 *
	 * <p>
	 * A null engine yields an empty response rather than an error. A run whose engine is gone — lost
	 * to a restart, or never live — is genuinely holding nothing and arming nothing, and saying so is
	 * more useful than a failure the caller would have to special-case anyway.
	 * </p>
	 */
	default io.metaloom.loom.rest.model.pipeline.PipelineBreakpointResponse toBreakpointResponse(
		io.metaloom.loom.pipeline.engine.PipelineRunEngine engine) {
		io.metaloom.loom.rest.model.pipeline.PipelineBreakpointResponse response =
			new io.metaloom.loom.rest.model.pipeline.PipelineBreakpointResponse();
		if (engine == null) {
			return response;
		}
		response.setNodeIds(new java.util.ArrayList<>(engine.getBreakpoints()));
		response.setHeld(engine.heldExecutions().stream()
			.map(held -> new io.metaloom.loom.rest.model.pipeline.PipelineHeldExecution()
				.setNodeId(held.nodeId())
				.setItemUuid(held.itemId())
				.setElementSeq(held.elementSeq()))
			.toList());
		return response;
	}

}
