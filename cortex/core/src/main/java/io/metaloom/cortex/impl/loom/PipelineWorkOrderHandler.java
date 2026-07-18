package io.metaloom.cortex.impl.loom;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;
import io.vertx.core.json.JsonObject;

/**
 * Handles the remaining work-order commands.
 *
 * <p>This class used to drive pipeline execution on Cortex: it resolved a pipeline
 * by name, expanded path globs, and ran the local DAG executor. All of that moved
 * to Loom — the engine there owns the graph and dispatches individual node tasks,
 * which arrive as {@code NODE_TASK} and are handled by {@link PipelineTaskHandler}.
 * The {@code run-pipeline}, {@code reload-pipelines} and {@code list-pipelines}
 * commands went with it: Cortex no longer holds pipelines to run, reload or list.</p>
 *
 * <p>What remains is {@code flush-sync}, which is genuinely Cortex-local — it drains
 * results computed here that have not yet been pushed to Loom.</p>
 */
@Singleton
public class PipelineWorkOrderHandler {

	private static final Logger log = LoggerFactory.getLogger(PipelineWorkOrderHandler.class);

	private static final String COMMAND_FLUSH_SYNC = "flush-sync";

	private final LoomBulkSyncCollector syncCollector;

	@Inject
	public PipelineWorkOrderHandler(LoomBulkSyncCollector syncCollector) {
		this.syncCollector = syncCollector;
	}

	/**
	 * Execute a work order.
	 *
	 * @param workOrder the order
	 * @return the outcome, never null
	 */
	public WorkOrderResult handle(WorkOrder workOrder) {
		WorkOrderResult result = new WorkOrderResult().setWorkOrderId(workOrder.getWorkOrderId());
		String command = resolveCommand(workOrder);
		try {
			if (!COMMAND_FLUSH_SYNC.equals(command)) {
				// Either a removed command or one this worker never supported.
				// Fail loudly: a silent success here would look like work happened.
				throw new IllegalArgumentException("Unsupported work order command: '" + command
					+ "'. Only '" + COMMAND_FLUSH_SYNC + "' remains - pipeline execution is driven "
					+ "by NODE_TASK messages from Loom.");
			}
			int flushed = syncCollector.flush();
			log.info("Flushed {} pending sync entries", flushed);
			return result
				.setStatus(WorkOrderStatus.COMPLETED)
				.setResult(new JsonObject().put("flushedSyncEntries", flushed));
		} catch (Exception e) {
			log.error("Work order {} ({}) failed", workOrder.getWorkOrderId(), command, e);
			return result
				.setStatus(WorkOrderStatus.FAILED)
				.setErrorMessage(e.getMessage());
		}
	}

	/**
	 * @param workOrder the order
	 * @return the command name, or null when none was supplied
	 */
	private String resolveCommand(WorkOrder workOrder) {
		JsonObject parameters = workOrder.getParameters();
		if (parameters != null) {
			String command = parameters.getString("command");
			if (command != null && !command.isBlank()) {
				return command;
			}
		}
		return workOrder.getType() != null ? workOrder.getType().name() : null;
	}
}
