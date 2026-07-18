package io.metaloom.cortex.impl.loom;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.node.source.fs.FilesystemMediaScanner;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineExecutor;
import io.metaloom.cortex.pipeline.api.PipelineManager;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.PipelineRunContext;
import io.metaloom.cortex.pipeline.loader.LoomPipelineLoader;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderType;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineWorkOrderHandler {

	private static final Logger log = LoggerFactory.getLogger(PipelineWorkOrderHandler.class);

	private final PipelineExecutor pipelineExecutor;
	private final PipelineManager pipelineManager;
	private final LoomPipelineLoader pipelineLoader;
	private final LoomMediaLoader mediaLoader;

	@Inject
	public PipelineWorkOrderHandler(PipelineExecutor pipelineExecutor, PipelineManager pipelineManager,
			LoomPipelineLoader pipelineLoader, LoomMediaLoader mediaLoader) {
		this.pipelineExecutor = pipelineExecutor;
		this.pipelineManager = pipelineManager;
		this.pipelineLoader = pipelineLoader;
		this.mediaLoader = mediaLoader;
	}

	public WorkOrderResult handle(WorkOrder workOrder) {
		WorkOrderResult result = new WorkOrderResult().setWorkOrderId(workOrder.getWorkOrderId());
		JsonObject payload = new JsonObject();
		try {
			String command = resolveCommand(workOrder);
			switch (command) {
				case "reload-pipelines":
					int loaded = pipelineLoader.loadAndRegister();
					payload.put("pipelinesLoaded", loaded);
					break;
				case "flush-sync":
					int flushed = pipelineExecutor.flushSync();
					payload.put("flushedSyncEntries", flushed);
					break;
				case "list-pipelines":
					List<String> names = pipelineManager.pipelines().stream().map(p -> p.name()).collect(Collectors.toList());
					payload.put("pipelineNames", new JsonArray(names));
					payload.put("pipelineCount", names.size());
					break;
				case "run-pipeline":
					handleRunPipeline(workOrder, payload);
					break;
				default:
					throw new IllegalArgumentException("Unsupported work-order command: " + command);
			}
			result.setStatus(WorkOrderStatus.COMPLETED).setResult(payload);
			log.info("Processed work order {} using command '{}'", workOrder.getWorkOrderId(), command);
		} catch (Exception e) {
			log.error("Failed to process work order {}", workOrder.getWorkOrderId(), e);
			result.setStatus(WorkOrderStatus.FAILED).setErrorMessage(e.getMessage()).setResult(payload);
		}
		return result;
	}

	private String resolveCommand(WorkOrder workOrder) {
		if (workOrder.getParameters() != null) {
			String command = workOrder.getParameters().getString("command");
			if (command != null && !command.isBlank()) {
				return command;
			}
		}
		WorkOrderType type = workOrder.getType();
		if (type == null) {
			throw new IllegalArgumentException("Work order has no type");
		}
		return switch (type) {
			case FILESYSTEM_SCAN -> "reload-pipelines";
			case FINGERPRINT -> "flush-sync";
			case PIPELINE_RUN -> "run-pipeline";
		};
	}

	/**
	 * Handle a {@code run-pipeline} command. The pipeline is resolved by name from
	 * the work-order parameters and executed either against an explicit selection
	 * given in those parameters, or — when none is given — against the selection
	 * owned by the pipeline's own source node.
	 *
	 * <p>The execution runs asynchronously on an IO scheduler; the work-order
	 * response reports what was dispatched, not the eventual pipeline result —
	 * progress is observable via the pipeline events WebSocket.</p>
	 */
	private void handleRunPipeline(WorkOrder workOrder, JsonObject payload) throws IOException {
		JsonObject params = workOrder.getParameters();
		if (params == null) {
			throw new IllegalArgumentException("run-pipeline: missing parameters");
		}
		String pipelineName = params.getString("pipelineName");
		if (pipelineName == null || pipelineName.isBlank()) {
			throw new IllegalArgumentException("run-pipeline: missing 'pipelineName' parameter");
		}
		
		// Extract pipeline run UUID for tracking
		String pipelineRunUuidStr = params.getString("pipelineRunUuid");
		UUID pipelineRunUuid = pipelineRunUuidStr != null ? UUID.fromString(pipelineRunUuidStr) : null;

		Optional<Pipeline> maybe = pipelineManager.pipeline(pipelineName);
		if (maybe.isEmpty()) {
			throw new IllegalStateException("run-pipeline: no pipeline registered with name '" + pipelineName + "'");
		}
		Pipeline pipeline = maybe.get();

		payload.put("pipelineName", pipelineName);
		if (pipelineRunUuid != null) {
			payload.put("pipelineRunUuid", pipelineRunUuid.toString());
		}

		// Correlate the tracking events emitted by this execution with the Loom
		// pipeline_run record, so Loom can close the run out when it completes.
		PipelineRunContext runContext = PipelineRunContext.of(
			pipelineRunUuid != null ? pipelineRunUuid.toString() : null);

		// A work order may narrow the run to an explicit media selection. Only
		// when it requests none at all does the pipeline's own source node decide
		// what to process — the handler no longer discovers media itself.
		//
		// A requested-but-unresolvable selection must never fall through to the
		// source node: that would widen the run from the handful of items the
		// caller asked for to everything the source is configured to scan.
		Flowable<PipelineResult> execution;
		if (hasExplicitSelection(params)) {
			List<LoomMedia> media = resolveSelection(params);
			payload.put("selection", "explicit");
			payload.put("mediaCount", media.size());
			if (media.isEmpty()) {
				log.warn("run-pipeline: no media resolved for pipeline '{}' — pipeline will not execute", pipelineName);
				payload.put("message", "no media resolved from selection");
				return;
			}
			execution = pipelineExecutor.execute(pipeline, Flowable.fromIterable(media), runContext);
			payload.put("message", "dispatched " + media.size() + " media items");
		} else {
			execution = pipelineExecutor.execute(pipeline, runContext);
			payload.put("selection", "source-node");
			payload.put("message", "dispatched pipeline '" + pipelineName + "' using its configured source node");
		}

		execution
			.subscribeOn(Schedulers.io())
			.subscribe(
				res -> log.debug("Pipeline '{}' processed media {}", pipelineName, res.getMedia().absolutePath()),
				err -> log.error("Pipeline '{}' execution error", pipelineName, err),
				() -> log.info("Pipeline '{}' execution completed", pipelineName));
	}

	/**
	 * Whether the work order asked for a specific set of media rather than
	 * leaving the selection to the pipeline's source node.
	 */
	private static boolean hasExplicitSelection(JsonObject params) {
		return isNonEmptyArray(params.getJsonArray("pathGlobs"))
			|| isNonEmptyArray(params.getJsonArray("mediaUuids"));
	}

	/**
	 * Resolve an explicit media selection. Path globs are expanded via
	 * {@link FilesystemMediaScanner}; UUID-based selection is not implemented yet
	 * and contributes nothing.
	 */
	private List<LoomMedia> resolveSelection(JsonObject params) throws IOException {
		if (isNonEmptyArray(params.getJsonArray("mediaUuids"))) {
			// UUID → path resolution requires a Loom client lookup which is not
			// wired into this handler yet. Warn so the gap is visible to the
			// caller rather than silently ignored.
			log.warn("run-pipeline: mediaUuids parameter received but UUID-based media resolution is not yet implemented; use pathGlobs");
		}
		return FilesystemMediaScanner.expand(stringList(params.getJsonArray("pathGlobs"))).stream()
			.map(mediaLoader::load)
			.collect(Collectors.toList());
	}

	private static boolean isNonEmptyArray(JsonArray array) {
		return array != null && !array.isEmpty();
	}

	private static List<String> stringList(JsonArray array) {
		if (array == null) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			String value = array.getString(i);
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return values;
	}

}
