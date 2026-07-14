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
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
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
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineEndpointService extends AbstractCRUDEndpointService<PipelineDao, Pipeline> {

	private static final Logger log = LoggerFactory.getLogger(PipelineEndpointService.class);

	private final ProcessorRegistry processorRegistry;
	private final NodeDescriptorRegistry nodeDescriptorRegistry;

	@Inject
	public PipelineEndpointService(PipelineDao pipelineDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, ProcessorRegistry processorRegistry,
		NodeDescriptorRegistry nodeDescriptorRegistry) {
		super(pipelineDao, daos, modelBuilder, validator);
		this.processorRegistry = processorRegistry;
		this.nodeDescriptorRegistry = nodeDescriptorRegistry;
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
			validateNodeTypes(request.getDefinition());

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
				validateNodeTypes(request.getDefinition());
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

			JsonObject params = new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineUuid", pipeline.getUuid().toString())
				.put("pipelineName", pipeline.getName());
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

			log.info("Pipeline '{}' run dispatched (workOrderId={}, processor={}, ok={})",
				pipeline.getName(), workOrderId, processor.nodeId, dispatched);
			lrc.send(response, dispatched ? 202 : 503);
		});
	}

	/**
	 * Validate that all node types in the pipeline definition are registered
	 * in the {@link NodeDescriptorRegistry}. This complements the structural
	 * validation in {@link PipelineModelValidator#validateDefinition} which checks
	 * node IDs, edges, and cycles.
	 *
	 * @param definition the pipeline definition JSON
	 * @throws ValidationException if an unknown node type is found
	 */
	private void validateNodeTypes(JsonObject definition) {
		if (definition == null) {
			return;
		}
		JsonArray nodes = definition.getJsonArray("nodes");
		if (nodes == null || nodes.isEmpty()) {
			return;
		}
		for (int i = 0; i < nodes.size(); i++) {
			JsonObject node = nodes.getJsonObject(i);
			if (node == null) {
				continue;
			}
			String type = node.getString("type");
			if (type == null || type.isBlank()) {
				continue; // structural validation handles missing type
			}
			if (!nodeDescriptorRegistry.contains(type)) {
				throw new ValidationException(
					"Unknown node type: \"" + type + "\" — not found in descriptor registry");
			}
		}
	}

}
