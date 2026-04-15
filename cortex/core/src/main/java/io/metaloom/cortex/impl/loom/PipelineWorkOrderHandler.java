package io.metaloom.cortex.impl.loom;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.pipeline.api.PipelineManager;
import io.metaloom.cortex.pipeline.api.PipelineExecutor;
import io.metaloom.cortex.pipeline.loader.LoomPipelineLoader;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineWorkOrderHandler {

	private static final Logger log = LoggerFactory.getLogger(PipelineWorkOrderHandler.class);

	private final PipelineExecutor pipelineExecutor;
	private final PipelineManager pipelineManager;
	private final LoomPipelineLoader pipelineLoader;

	@Inject
	public PipelineWorkOrderHandler(PipelineExecutor pipelineExecutor, PipelineManager pipelineManager,
			LoomPipelineLoader pipelineLoader) {
		this.pipelineExecutor = pipelineExecutor;
		this.pipelineManager = pipelineManager;
		this.pipelineLoader = pipelineLoader;
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
		};
	}

}
