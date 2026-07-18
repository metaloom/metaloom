package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE_VERSION;
import static io.metaloom.loom.db.model.perm.Permission.RESTORE_PIPELINE_VERSION;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionRestoreRequest;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderType;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.ValidationException;
import io.metaloom.loom.rest.validation.PipelineValidationService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineEndpointService extends AbstractCRUDEndpointService<PipelineDao, Pipeline> {

	private static final Logger log = LoggerFactory.getLogger(PipelineEndpointService.class);

	/**
	 * How long to wait for a processor to acknowledge a dispatched pipeline work
	 * order before declaring the run failed. This is a <em>dispatch</em> watchdog,
	 * not a run timeout: the ack arrives as soon as the processor has resolved the
	 * media selection and started executing, long before the pipeline finishes.
	 */
	private static final long WORK_ORDER_ACK_TIMEOUT_MS = 60_000;

	private final ProcessorRegistry processorRegistry;
	private final PipelineValidationService pipelineValidationService;
	private final PipelineRunDao pipelineRunDao;
	private final PipelineVersionDao pipelineVersionDao;
	private final WorkOrderResultRegistry workOrderResultRegistry;
	private final PipelineRunTracker pipelineRunTracker;

	@Inject
	public PipelineEndpointService(PipelineDao pipelineDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, ProcessorRegistry processorRegistry,
		PipelineValidationService pipelineValidationService, PipelineRunDao pipelineRunDao,
		PipelineVersionDao pipelineVersionDao, WorkOrderResultRegistry workOrderResultRegistry,
		PipelineRunTracker pipelineRunTracker) {
		super(pipelineDao, daos, modelBuilder, validator);
		this.processorRegistry = processorRegistry;
		this.pipelineValidationService = pipelineValidationService;
		this.pipelineRunDao = pipelineRunDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.workOrderResultRegistry = workOrderResultRegistry;
		this.pipelineRunTracker = pipelineRunTracker;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, DELETE_PIPELINE, () -> {
			// Delete all versions for this pipeline first
			pipelineVersionDao.loadByPipeline(id).forEach(v -> pipelineVersionDao.delete(v.getUuid()));
			// Then delete the pipeline
			dao().delete(id);
			lrc.send(new io.metaloom.loom.rest.model.message.GenericMessageResponse().setMessage("Pipeline and all versions deleted"));
		});
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PIPELINE, page -> modelBuilder.toPipelineList(page, latestVersions(page)));
	}

	/**
	 * Resolve the latest version of every pipeline on the page in a single query, so that the flattened response can be built without an N+1 lookup.
	 *
	 * <p>
	 * This follows the {@code latestVersionUuid} pointer rather than re-deriving the highest version number per pipeline, which is what makes the batch
	 * possible. Create, update and restore all keep the pointer in step, and the V2.30 migration backfilled it, so the two agree.
	 * </p>
	 */
	private Map<UUID, PipelineVersion> latestVersions(io.metaloom.loom.db.page.Page<Pipeline> page) {
		Map<UUID, UUID> versionToPipeline = new HashMap<>();
		for (Pipeline pipeline : page) {
			if (pipeline.getLatestVersionUuid() != null) {
				versionToPipeline.put(pipeline.getLatestVersionUuid(), pipeline.getUuid());
			}
		}
		Map<UUID, PipelineVersion> byPipeline = new HashMap<>();
		for (PipelineVersion version : pipelineVersionDao.loadByUuids(versionToPipeline.keySet())) {
			byPipeline.put(versionToPipeline.get(version.getUuid()), version);
		}
		return byPipeline;
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PIPELINE, () -> {
			return dao().loadWithLatestVersion(id);
		}, pipeline -> modelBuilder.toResponse(pipeline, pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid())));
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		AtomicReference<PipelineVersion> created = new AtomicReference<>();
		create(lrc, CREATE_PIPELINE, () -> {
			PipelineCreateRequest request = lrc.requestBody(PipelineCreateRequest.class);
			validator.validate(request);
			pipelineValidationService.validateDefinition(request.getDefinition());

			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().createPipeline(userUuid, request.getName());
			pipeline.setMeta(request.getMeta());
			dao().store(pipeline);

			// Create v1 in pipeline_version table
			PipelineVersion version = pipelineVersionDao.createVersion(
				userUuid,
				pipeline.getUuid(),
				1,
				request.getName(),
				request.getDescription(),
				request.getDefinition(),
				request.isEnabled() != null ? request.isEnabled() : true,
				request.getPriority() != null ? request.getPriority() : 0,
				request.isDryRun() != null ? request.isDryRun() : false,
				request.getMeta()
			);
			pipelineVersionDao.store(version);
			created.set(version);

			// Update pipeline with latest version reference
			pipeline.setLatestVersionUuid(version.getUuid());
			dao().update(pipeline);

			return pipeline;
		}, pipeline -> modelBuilder.toResponse(pipeline, created.get()));
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		AtomicReference<PipelineVersion> updated = new AtomicReference<>();
		update(lrc, UPDATE_PIPELINE, () -> {
			PipelineUpdateRequest request = lrc.requestBody(PipelineUpdateRequest.class);
			validator.validate(request);
			if (request.getDefinition() != null) {
				pipelineValidationService.validateDefinition(request.getDefinition());
			}

			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().loadWithLatestVersion(id);
			if (pipeline == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline not found.");
			}

			// Get the latest version to determine the next version number
			PipelineVersion latestVersion = pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid());
			int nextVersion = latestVersion != null ? latestVersion.getVersionNumber() + 1 : 1;

			// Create new version with updated data
			PipelineVersion version = pipelineVersionDao.createVersion(
				userUuid,
				pipeline.getUuid(),
				nextVersion,
				request.getName() != null ? request.getName() : latestVersion.getName(),
				request.getDescription() != null ? request.getDescription() : latestVersion.getDescription(),
				request.getDefinition() != null ? request.getDefinition() : latestVersion.getDefinition(),
				request.isEnabled() != null ? request.isEnabled() : latestVersion.isEnabled(),
				request.getPriority() != null ? request.getPriority() : latestVersion.getPriority(),
				request.isDryRun() != null ? request.isDryRun() : latestVersion.isDryRun(),
				request.getMeta() != null ? request.getMeta() : latestVersion.getMeta()
			);
			pipelineVersionDao.store(version);
			updated.set(version);

			// Update pipeline with latest version reference and meta
			pipeline.setLatestVersionUuid(version.getUuid());
			if (request.getMeta() != null) {
				pipeline.setMeta(request.getMeta());
			}
			setEditor(pipeline, userUuid);
			dao().update(pipeline);

			return pipeline;
		}, pipeline -> modelBuilder.toResponse(pipeline, updated.get()));
	}

	/**
	 * Trigger a pipeline run by dispatching a {@link WorkOrder} of type
	 * {@link WorkOrderType#PIPELINE_RUN} to a registered processor.
	 *
	 * <p>Callers only need {@link io.metaloom.loom.db.model.perm.Permission#READ_PIPELINE}
	 * — this endpoint does not mutate the pipeline definition itself; it merely
	 * asks a processor to execute the already-persisted definition.</p>
	 */
	public void run(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, READ_PIPELINE, () -> {
			Pipeline pipeline = dao().loadWithLatestVersion(id);
			if (pipeline == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline not found.");
			}

			PipelineRunRequest request;
			try {
				request = lrc.requestBody(PipelineRunRequest.class);
			} catch (Exception e) {
				request = new PipelineRunRequest();
			}
			if (request == null) {
				request = new PipelineRunRequest();
			}

			ConnectedProcessor processor = processorRegistry.selectProcessor(ProcessorCapability.CPU);
			PipelineRunResponse response = new PipelineRunResponse();
			UUID workOrderId = UUID.randomUUID();
			response.setWorkOrderId(workOrderId);

			if (processor == null) {
				response.setDispatched(false).setMessage("No processor available");
				log.warn("Rejected pipeline run for pipeline {}: no processor registered", pipeline.getUuid());
				lrc.send(response, 503);
				return;
			}

			// Get the latest version for the run
			PipelineVersion latestVersion = pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid());
			int pipelineVersion = latestVersion != null ? latestVersion.getVersionNumber() : 1;

			// Create a pipeline run record to track this execution
			PipelineRun runRecord = pipelineRunDao.createPipelineRun(lrc.userUuid(), pipeline.getUuid(), pipelineVersion);
			runRecord.setStatus("RUNNING");
			runRecord.setDryRun(request.isDryRun() != null ? request.isDryRun() : (latestVersion != null ? latestVersion.isDryRun() : false));
			pipelineRunDao.store(runRecord);

			JsonObject params = new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineUuid", pipeline.getUuid().toString())
				.put("pipelineName", latestVersion != null ? latestVersion.getName() : "unknown")
				.put("pipelineRunUuid", runRecord.getUuid().toString())
				.put("pipelineVersion", pipelineVersion);
			if (request.getMediaUuids() != null && !request.getMediaUuids().isEmpty()) {
				params.put("mediaUuids", new io.vertx.core.json.JsonArray(
					request.getMediaUuids().stream().map(u -> u.toString()).toList()));
			}
			if (request.getPathGlobs() != null && !request.getPathGlobs().isEmpty()) {
				params.put("pathGlobs", new io.vertx.core.json.JsonArray(request.getPathGlobs()));
			}
			if (request.isDryRun() != null) {
				params.put("dryRun", request.isDryRun());
			}

			WorkOrder workOrder = new WorkOrder()
				.setWorkOrderId(workOrderId)
				.setType(WorkOrderType.PIPELINE_RUN)
				.setRequiredCapability(ProcessorCapability.CPU)
				.setAssetUuids(request.getMediaUuids())
				.setParameters(params);

			// Watch for the processor's acknowledgement so a work order that is never
			// picked up does not strand the run at RUNNING forever. Note this is the
			// *dispatch* ack, not run completion — a successful ack leaves the run
			// RUNNING and the terminal state arrives later via PIPELINE_RUN_COMPLETED.
			UUID runUuid = runRecord.getUuid();
			workOrderResultRegistry.registerWithTimeout(workOrderId,
				result -> onWorkOrderAck(runUuid, result), WORK_ORDER_ACK_TIMEOUT_MS);

			boolean dispatched = processorRegistry.dispatchWorkOrder(processor.nodeId, workOrder);
			if (!dispatched) {
				// The socket was gone by the time we wrote to it. Nothing will ever
				// acknowledge this work order, so close the run out immediately
				// rather than waiting for the watchdog.
				workOrderResultRegistry.cancel(workOrderId);
				pipelineRunTracker.fail(runUuid, "Processor was not reachable");
			}
			response
				.setProcessorNodeId(processor.nodeId)
				.setDispatched(dispatched)
				.setMessage(dispatched ? "Work order dispatched" : "Processor was not reachable");

			log.info("Pipeline '{}' run dispatched (workOrderId={}, pipelineRunUuid={}, processor={}, ok={})",
				latestVersion != null ? latestVersion.getName() : pipeline.getUuid(), workOrderId, runRecord.getUuid(), processor.nodeId, dispatched);
			lrc.send(response, dispatched ? 202 : 503);
		});
	}

	/**
	 * Handle the processor's acknowledgement of a dispatched pipeline work order.
	 *
	 * <p>The ack reports whether the processor accepted and started the work, not
	 * whether the pipeline finished. Three outcomes matter:</p>
	 * <ul>
	 *   <li><b>FAILED</b> — the processor could not start (unknown pipeline, bad
	 *       parameters, timed-out watchdog). The run is closed as FAILED.</li>
	 *   <li><b>COMPLETED with zero media</b> — the selection resolved to nothing,
	 *       so the pipeline never runs and will never emit PIPELINE_RUN_COMPLETED.
	 *       Close the run out now, otherwise it strands at RUNNING.</li>
	 *   <li><b>COMPLETED with media</b> — execution is under way. Leave the run
	 *       RUNNING; {@code ProcessorEndpoint} closes it when the terminal
	 *       PIPELINE_RUN_COMPLETED message arrives.</li>
	 * </ul>
	 */
	private void onWorkOrderAck(UUID runUuid, WorkOrderResult result) {
		if (result == null) {
			return;
		}
		if (result.getStatus() == WorkOrderStatus.FAILED) {
			String error = result.getErrorMessage() != null ? result.getErrorMessage()
				: "Processor reported work order failure";
			log.warn("Pipeline run {} failed at dispatch: {}", runUuid, error);
			pipelineRunTracker.fail(runUuid, error);
			return;
		}

		JsonObject payload = result.getResult();
		Integer mediaCount = payload != null ? payload.getInteger("mediaCount") : null;
		if (mediaCount != null && mediaCount == 0) {
			log.info("Pipeline run {} resolved no media — closing out immediately", runUuid);
			pipelineRunTracker.complete(runUuid, 0L, 0, 0, 0, 0);
			return;
		}

		log.debug("Pipeline run {} acknowledged by processor ({} media dispatched) — awaiting completion",
			runUuid, mediaCount);
	}

	/**
	 * List pipeline runs for a specific pipeline.
	 */
	public void listRuns(LoomRoutingContext lrc, UUID pipelineUuid) {
		checkPerm(lrc, READ_PIPELINE, () -> {
			io.metaloom.loom.rest.parameter.PagingParameters pagingParameters = lrc.pagingParams();
			io.metaloom.loom.rest.parameter.FilterParameters filterParameters = lrc.filterParams();
			io.metaloom.loom.rest.parameter.SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading page from {} limit: {}", from, limit);
			}
			io.metaloom.loom.db.page.Page<PipelineRun> page = pipelineRunDao.loadPageByPipeline(pipelineUuid, from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			io.metaloom.loom.rest.model.RestResponseModel<?> response = modelBuilder.toPipelineRunList(page);
			lrc.send(response);
		});
	}

	/**
	 * List all versions of a pipeline.
	 */
	public void listVersions(LoomRoutingContext lrc, UUID pipelineUuid) {
		checkPerm(lrc, READ_PIPELINE_VERSION, () -> {
			io.metaloom.loom.rest.parameter.PagingParameters pagingParameters = lrc.pagingParams();
			io.metaloom.loom.rest.parameter.FilterParameters filterParameters = lrc.filterParams();
			io.metaloom.loom.rest.parameter.SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading versions page from {} limit: {}", from, limit);
			}
			io.metaloom.loom.db.page.Page<PipelineVersion> page = pipelineVersionDao.loadPageByPipeline(pipelineUuid, from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			io.metaloom.loom.rest.model.RestResponseModel<?> response = modelBuilder.toPipelineVersionList(pipelineUuid, page);
			lrc.send(response);
		});
	}

	/**
	 * Load a specific version of a pipeline.
	 */
	public void loadVersion(LoomRoutingContext lrc, UUID pipelineUuid, int versionNumber) {
		checkPerm(lrc, READ_PIPELINE_VERSION, () -> {
			PipelineVersion version = pipelineVersionDao.loadByPipelineAndVersion(pipelineUuid, versionNumber);
			if (version == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline version not found.");
			}
			PipelineResponse response = modelBuilder.toVersionResponse(pipelineUuid, version);
			lrc.send(response);
		});
	}

	/**
	 * Restore a pipeline version (creates a new version with the restored content).
	 */
	public void restoreVersion(LoomRoutingContext lrc, UUID pipelineUuid, int versionNumber) {
		checkPerm(lrc, RESTORE_PIPELINE_VERSION, () -> {
			PipelineVersion versionToRestore = pipelineVersionDao.loadByPipelineAndVersion(pipelineUuid, versionNumber);
			if (versionToRestore == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline version not found.");
			}

			PipelineVersionRestoreRequest request;
			try {
				request = lrc.requestBody(PipelineVersionRestoreRequest.class);
			} catch (Exception e) {
				request = new PipelineVersionRestoreRequest();
			}
			if (request == null) {
				request = new PipelineVersionRestoreRequest();
			}

			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().loadWithLatestVersion(pipelineUuid);
			if (pipeline == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline not found.");
			}

			// Get the latest version to determine the next version number
			PipelineVersion latestVersion = pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid());
			int nextVersion = latestVersion != null ? latestVersion.getVersionNumber() + 1 : 1;

			// Create new version with restored content
			PipelineVersion restoredVersion = pipelineVersionDao.createVersion(
				userUuid,
				pipeline.getUuid(),
				nextVersion,
				request.getName() != null ? request.getName() : versionToRestore.getName(),
				request.getDescription() != null ? request.getDescription() : versionToRestore.getDescription(),
				versionToRestore.getDefinition(),
				versionToRestore.isEnabled(),
				versionToRestore.getPriority(),
				versionToRestore.isDryRun(),
				versionToRestore.getMeta()
			);
			pipelineVersionDao.store(restoredVersion);

			// Update pipeline with latest version reference
			pipeline.setLatestVersionUuid(restoredVersion.getUuid());
			setEditor(pipeline, userUuid);
			dao().update(pipeline);

			// The restore created a new latest version, so the pipeline renders from it.
			PipelineResponse response = modelBuilder.toResponse(pipeline, restoredVersion);
			lrc.send(response, 201);
		});
	}

}
