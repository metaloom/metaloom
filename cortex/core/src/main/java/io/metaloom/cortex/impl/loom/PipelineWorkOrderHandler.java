package io.metaloom.cortex.impl.loom;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineExecutor;
import io.metaloom.cortex.pipeline.api.PipelineManager;
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
	 * Handle a {@code run-pipeline} command. The pipeline is resolved by name
	 * from the work-order parameters and executed against a media selection
	 * built from {@code pathGlobs}. The execution runs asynchronously on an IO
	 * scheduler; the work-order response reports the number of media items
	 * that were dispatched, not the eventual pipeline result — progress is
	 * observable via the pipeline events WebSocket.
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

		List<LoomMedia> media = collectMedia(params);
		payload.put("pipelineName", pipelineName);
		payload.put("mediaCount", media.size());

		if (pipelineRunUuid != null) {
			payload.put("pipelineRunUuid", pipelineRunUuid.toString());
		}

		if (media.isEmpty()) {
			log.warn("run-pipeline: no media resolved for pipeline '{}' — pipeline will not execute", pipelineName);
			payload.put("message", "no media resolved from selection");
			return;
		}

		Flowable<LoomMedia> stream = Flowable.fromIterable(media);
		pipelineExecutor.execute(pipeline, stream)
			.subscribeOn(Schedulers.io())
			.subscribe(
				res -> log.debug("Pipeline '{}' processed media {}", pipelineName, res.getMedia().absolutePath()),
				err -> log.error("Pipeline '{}' execution error", pipelineName, err),
				() -> log.info("Pipeline '{}' execution completed for {} media items", pipelineName, media.size()));
		payload.put("message", "dispatched " + media.size() + " media items");
	}

	/**
	 * Resolve a media selection from {@code pathGlobs}. Each glob is expanded
	 * against the current working directory; matching files are wrapped as
	 * {@link LoomMedia}. Non-existent paths are silently skipped.
	 */
	private List<LoomMedia> collectMedia(JsonObject params) throws IOException {
		List<LoomMedia> media = new ArrayList<>();
		JsonArray globs = params.getJsonArray("pathGlobs");
		if (globs != null) {
			for (int i = 0; i < globs.size(); i++) {
				String glob = globs.getString(i);
				if (glob == null || glob.isBlank()) {
					continue;
				}
				expandGlob(glob).forEach(p -> media.add(mediaLoader.load(p)));
			}
		}
		if (params.getJsonArray("mediaUuids") != null && !params.getJsonArray("mediaUuids").isEmpty()) {
			// UUID → path resolution requires a Loom client lookup which is not
			// wired into this handler yet. Emit a warning so the caller sees the
			// gap in the work-order response.
			log.warn("run-pipeline: mediaUuids parameter received but UUID-based media resolution is not yet implemented; use pathGlobs");
		}
		return media;
	}

	private static List<Path> expandGlob(String glob) throws IOException {
		Path base = Paths.get(".").toAbsolutePath().normalize();
		// If the glob contains a fixed prefix without wildcards, walk from there
		int wildIdx = firstWildcardIndex(glob);
		if (wildIdx < 0) {
			// Literal path
			Path literal = Paths.get(glob).toAbsolutePath().normalize();
			return Files.exists(literal) ? List.of(literal) : List.of();
		}
		Path root = base;
		String pattern = glob;
		if (wildIdx > 0) {
			int lastSlash = glob.lastIndexOf('/', wildIdx);
			if (lastSlash > 0) {
				root = Paths.get(glob.substring(0, lastSlash)).toAbsolutePath().normalize();
				pattern = glob.substring(lastSlash + 1);
			}
		}
		if (!Files.exists(root)) {
			return List.of();
		}
		final Path effectiveRoot = root;
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		try (Stream<Path> stream = Files.walk(effectiveRoot)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(p -> matcher.matches(effectiveRoot.relativize(p)))
				.collect(Collectors.toList());
		}
	}

	private static int firstWildcardIndex(String glob) {
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*' || c == '?' || c == '[' || c == '{') {
				return i;
			}
		}
		return -1;
	}

}
