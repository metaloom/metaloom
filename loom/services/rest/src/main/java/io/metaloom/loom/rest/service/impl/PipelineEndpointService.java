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
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphNode;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;
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
	private final PipelineVersionDao pipelineVersionDao;
	private final PipelineRunTracker pipelineRunTracker;

	private final PipelineRunRegistry pipelineRunRegistry;

	private final WebSocketNodeDispatcher nodeDispatcher;

	private final PipelineGraphParser graphParser;

	@Inject
	public PipelineEndpointService(PipelineDao pipelineDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, ProcessorRegistry processorRegistry,
		PipelineValidationService pipelineValidationService, PipelineRunDao pipelineRunDao,
		PipelineVersionDao pipelineVersionDao, PipelineRunTracker pipelineRunTracker, PipelineRunRegistry pipelineRunRegistry,
		WebSocketNodeDispatcher nodeDispatcher) {
		super(pipelineDao, daos, modelBuilder, validator);
		this.processorRegistry = processorRegistry;
		this.pipelineValidationService = pipelineValidationService;
		this.pipelineRunDao = pipelineRunDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.pipelineRunTracker = pipelineRunTracker;
		this.pipelineRunRegistry = pipelineRunRegistry;
		this.nodeDispatcher = nodeDispatcher;
		this.graphParser = new PipelineGraphParser();
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
			String pipelineName = latestVersion != null ? latestVersion.getName() : String.valueOf(pipeline.getUuid());
			boolean dryRun = request.isDryRun() != null
				? request.isDryRun()
				: (latestVersion != null && latestVersion.isDryRun());

			// Build the executable graph up front. A definition that cannot run as
			// drawn is an error the caller should see now, not a green run that
			// quietly did nothing.
			PipelineGraph graph;
			try {
				graph = graphParser.parse(pipelineName,
					latestVersion != null ? latestVersion.getDefinition() : null,
					latestVersion == null || latestVersion.isEnabled(), dryRun, pipelineVersion);
			} catch (GraphValidationException e) {
				log.warn("Refusing to run pipeline '{}': {}", pipelineName, e.getMessage());
				response.setDispatched(false).setMessage(e.getMessage());
				lrc.send(response, 400);
				return;
			}

			// Create a pipeline run record to track this execution
			PipelineRun runRecord = pipelineRunDao.createPipelineRun(lrc.userUuid(), pipeline.getUuid(), pipelineVersion);
			runRecord.setStatus("RUNNING");
			runRecord.setDryRun(dryRun);
			pipelineRunDao.store(runRecord);
			UUID runUuid = runRecord.getUuid();

			// The engine owns the graph and decides what runs next; Cortex only ever
			// sees one node at a time.
			PipelineRunEngine engine = new PipelineRunEngine(graph, nodeDispatcher, runUuid);
			engine.onCompletion(summary -> pipelineRunTracker.complete(runUuid, summary.getDurationMs(),
				(int) summary.getMediaCount(), (int) summary.getSuccessCount(),
				(int) summary.getFailureCount(), (int) summary.getSkippedCount()));
			pipelineRunRegistry.register(runUuid, engine);
			engine.start();

			if (request.getMediaUuids() != null && !request.getMediaUuids().isEmpty()) {
				log.warn("Run {} requested {} media uuid(s); uuid-based selection is not implemented "
					+ "and only pathGlobs are honoured", runUuid, request.getMediaUuids().size());
			}

			// Hand the source node to a worker. Everything else follows from the
            // items it streams back.
			PipelineGraphNode sourceNode = graph.getSourceNode();
			SourceTaskMessage sourceTask = new SourceTaskMessage()
				.setRunUuid(runUuid)
				.setNodeId(sourceNode.getId())
				.setNodeKind(sourceNode.getKind())
				.setOptions(sourceOptions(sourceNode, request));

			boolean dispatched = processorRegistry.send(processor.nodeId, ProcessorMessageType.SOURCE_TASK, sourceTask);
			if (!dispatched) {
				// The socket was gone by the time we wrote to it. Nothing will ever
				// enumerate, so close the run out immediately rather than leaving it
				// RUNNING forever.
				pipelineRunRegistry.unregister(runUuid);
				pipelineRunTracker.fail(runUuid, "Processor was not reachable");
			}

			response
				.setProcessorNodeId(processor.nodeId)
				.setDispatched(dispatched)
				.setMessage(dispatched ? "Source task dispatched" : "Processor was not reachable");

			log.info("Pipeline '{}' run started (pipelineRunUuid={}, nodes={}, processor={}, ok={})",
				pipelineName, runUuid, graph.size(), processor.nodeId, dispatched);
			lrc.send(response, dispatched ? 202 : 503);
		});
	}

	/**
	 * Resolve the options the source node should run with.
	 *
	 * <p>The definition supplies the defaults; a run request may override the
	 * selection. Note the paths are resolved on the <em>worker</em>, so a path the
	 * chosen processor cannot see yields an empty run rather than an error.</p>
	 */
	private java.util.Map<String, Object> sourceOptions(PipelineGraphNode sourceNode, PipelineRunRequest request) {
		java.util.Map<String, Object> options = new java.util.LinkedHashMap<>(sourceNode.getOptions());
		if (request.getPathGlobs() != null && !request.getPathGlobs().isEmpty()) {
			options.put("pathGlobs", request.getPathGlobs());
		}
		return options;
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
