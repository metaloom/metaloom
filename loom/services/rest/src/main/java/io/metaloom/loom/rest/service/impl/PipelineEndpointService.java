package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PIPELINE;

import java.util.UUID;

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
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
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

	private final ProcessorRegistry processorRegistry;
	private final PipelineValidationService pipelineValidationService;
	private final PipelineRunDao pipelineRunDao;

	@Inject
	public PipelineEndpointService(PipelineDao pipelineDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, ProcessorRegistry processorRegistry,
		PipelineValidationService pipelineValidationService, PipelineRunDao pipelineRunDao) {
		super(pipelineDao, daos, modelBuilder, validator);
		this.processorRegistry = processorRegistry;
		this.pipelineValidationService = pipelineValidationService;
		this.pipelineRunDao = pipelineRunDao;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_PIPELINE, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PIPELINE, modelBuilder::toPipelineList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PIPELINE, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_PIPELINE, () -> {
			PipelineCreateRequest request = lrc.requestBody(PipelineCreateRequest.class);
			validator.validate(request);
			pipelineValidationService.validateDefinition(request.getDefinition());

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().createPipeline(userUuid, name);
			pipeline.setDescription(request.getDescription());
			pipeline.setDefinition(request.getDefinition());
			if (request.isEnabled() != null) {
				pipeline.setEnabled(request.isEnabled());
			}
			if (request.getPriority() != null) {
				pipeline.setPriority(request.getPriority());
			}
			if (request.isDryRun() != null) {
				pipeline.setDryRun(request.isDryRun());
			}
			update(request::getMeta, pipeline::setMeta);
			return pipeline;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_PIPELINE, () -> {
			PipelineUpdateRequest request = lrc.requestBody(PipelineUpdateRequest.class);
			validator.validate(request);
			if (request.getDefinition() != null) {
				pipelineValidationService.validateDefinition(request.getDefinition());
			}

			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().load(id);
			update(request::getName, pipeline::setName);
			update(request::getDescription, pipeline::setDescription);
			update(request::getDefinition, pipeline::setDefinition);
			if (request.isEnabled() != null) {
				pipeline.setEnabled(request.isEnabled());
			}
			if (request.getPriority() != null) {
				pipeline.setPriority(request.getPriority());
			}
			if (request.isDryRun() != null) {
				pipeline.setDryRun(request.isDryRun());
			}
			update(request::getMeta, pipeline::setMeta);
			setEditor(pipeline, userUuid);
			return pipeline;
		}, modelBuilder::toResponse);
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
			Pipeline pipeline = dao().load(id);
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
				log.warn("Rejected pipeline run for '{}': no processor registered", pipeline.getName());
				lrc.send(response, 503);
				return;
			}

			// Create a pipeline run record to track this execution
			PipelineRun runRecord = pipelineRunDao.createPipelineRun(lrc.userUuid(), pipeline.getUuid(), 1);
			runRecord.setStatus("RUNNING");
			runRecord.setDryRun(request.isDryRun() != null ? request.isDryRun() : pipeline.isDryRun());
			pipelineRunDao.store(runRecord);

			JsonObject params = new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineUuid", pipeline.getUuid().toString())
				.put("pipelineName", pipeline.getName())
				.put("pipelineRunUuid", runRecord.getUuid().toString());
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

			boolean dispatched = processorRegistry.dispatchWorkOrder(processor.nodeId, workOrder);
			response
				.setProcessorNodeId(processor.nodeId)
				.setDispatched(dispatched)
				.setMessage(dispatched ? "Work order dispatched" : "Processor was not reachable");

			log.info("Pipeline '{}' run dispatched (workOrderId={}, pipelineRunUuid={}, processor={}, ok={})",
				pipeline.getName(), workOrderId, runRecord.getUuid(), processor.nodeId, dispatched);
			lrc.send(response, dispatched ? 202 : 503);
		});
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

}
