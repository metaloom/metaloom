package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.searchindex.IndexJobCreateRequest;
import io.metaloom.loom.rest.model.searchindex.IndexJobListResponse;
import io.metaloom.loom.rest.model.searchindex.IndexJobResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexBackendResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexListResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexResponse;
import io.metaloom.loom.rest.search.IndexJob;
import io.metaloom.loom.rest.search.IndexJobAction;
import io.metaloom.loom.rest.search.IndexJobRegistry;
import io.metaloom.loom.rest.search.SearchIndexBackend;
import io.metaloom.loom.rest.search.SearchIndexDescriptor;
import io.metaloom.loom.rest.search.SearchIndexJobRunner;
import io.metaloom.loom.rest.search.SearchIndexRegistry;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Serves the search index admin routes.
 *
 * <p>
 * <b>Reading never fails because an index is broken.</b> {@code GET /search-indices} answers 200 with each index's own state, including "the directory
 * could not be opened" - this is the screen an operator reaches for when something is already wrong, and a 503 here would hide the diagnosis behind
 * the symptom. The job routes are the opposite: starting work against an unusable index answers 503 naming the reason rather than reporting a
 * successful rebuild of nothing.
 * </p>
 *
 * <p>
 * <b>Unsupported actions are rejected server-side.</b> Each descriptor advertises what it accepts so the UI can hide a button, but the check lives
 * here too - hiding a control is a courtesy, not an authorization decision, and {@code DROP} on the lexical index would otherwise be one curl away
 * from emptying search until the next rebuild.
 * </p>
 */
@Singleton
public class SearchIndexEndpointService extends AbstractEndpointService {

	private final SearchIndexRegistry registry;
	private final IndexJobRegistry jobs;
	private final SearchIndexJobRunner runner;

	@Inject
	public SearchIndexEndpointService(SearchIndexRegistry registry, IndexJobRegistry jobs, SearchIndexJobRunner runner,
		LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.registry = registry;
		this.jobs = jobs;
		this.runner = runner;
	}

	/** {@code GET /api/v1/search-indices} - every index and the backends they live in. */
	public void list(LoomRoutingContext lrc) {
		checkPerm(lrc, Permission.READ_SEARCH_INDEX, () -> {
			SearchIndexListResponse response = new SearchIndexListResponse();
			for (SearchIndexDescriptor descriptor : registry.list()) {
				response.getData().add(toResponse(descriptor));
			}
			for (SearchIndexBackend backend : registry.backends()) {
				response.getBackends().add(toResponse(backend));
			}
			lrc.send(response);
		});
	}

	/** {@code GET /api/v1/search-indices/:id} */
	public void read(LoomRoutingContext lrc, String id) {
		checkPerm(lrc, Permission.READ_SEARCH_INDEX, () -> lrc.send(toResponse(require(id))));
	}

	/** {@code GET /api/v1/search-indices/:id/jobs} - recent jobs, newest first. */
	public void listJobs(LoomRoutingContext lrc, String id) {
		checkPerm(lrc, Permission.READ_SEARCH_INDEX, () -> {
			SearchIndexDescriptor index = require(id);
			IndexJobListResponse response = new IndexJobListResponse();
			for (IndexJob job : jobs.list(index.id())) {
				response.getData().add(toResponse(job));
			}
			lrc.send(response);
		});
	}

	/** {@code GET /api/v1/search-indices/:id/jobs/:jobUuid} */
	public void readJob(LoomRoutingContext lrc, String id, UUID jobUuid) {
		checkPerm(lrc, Permission.READ_SEARCH_INDEX, () -> {
			SearchIndexDescriptor index = require(id);
			lrc.send(toResponse(requireJob(index, jobUuid)));
		});
	}

	/**
	 * {@code POST /api/v1/search-indices/:id/jobs} - start a job.
	 *
	 * <p>
	 * Answers <b>202</b> with the job, not 200 with a result: a reindex walks the whole corpus, and holding an HTTP connection open for the minutes
	 * that takes is what this endpoint exists to replace. The client polls the returned job for progress.
	 * </p>
	 */
	public void createJob(LoomRoutingContext lrc, String id) {
		checkPerm(lrc, Permission.MANAGE_SEARCH_INDEX, () -> {
			SearchIndexDescriptor index = require(id);
			IndexJobAction action = parseAction(lrc.requestBody(IndexJobCreateRequest.class));

			if (!index.supports(action)) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"The index " + index.id() + " does not support " + action + ". Supported: " + index.supportedActions() + ".");
			}
			if (!index.available()) {
				throw new LoomRestException(503, LoomRestErrorCode.SEARCH_UNAVAILABLE,
					"The index " + index.id() + " is unavailable: " + index.reason());
			}

			IndexJob job = jobs.submit(index.id(), action, running -> runner.run(index, running));
			lrc.send(toResponse(job), 202);
		});
	}

	/**
	 * {@code DELETE /api/v1/search-indices/:id/jobs/:jobUuid} - ask a running job to stop.
	 *
	 * <p>
	 * Cooperative, so this returns the job as it was at the moment of asking and the state becomes {@code CANCELLED} once the worker reaches its next
	 * item boundary. A job that has already finished is returned unchanged rather than 409'd - "stop something that stopped" is satisfied.
	 * </p>
	 */
	public void cancelJob(LoomRoutingContext lrc, String id, UUID jobUuid) {
		checkPerm(lrc, Permission.MANAGE_SEARCH_INDEX, () -> {
			SearchIndexDescriptor index = require(id);
			IndexJob job = requireJob(index, jobUuid);
			if (!job.getState().isTerminal()) {
				job.requestCancel();
			}
			lrc.send(toResponse(job));
		});
	}

	// ---------------------------------------------------------------------------------------------

	private SearchIndexDescriptor require(String id) {
		SearchIndexDescriptor descriptor = registry.find(id);
		if (descriptor == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No search index with id " + id + ".");
		}
		return descriptor;
	}

	/**
	 * A job is addressed beneath its index, so a uuid belonging to a different index is a 404 rather than a redirect - the pair has to agree or the
	 * client is polling something it did not start.
	 */
	private IndexJob requireJob(SearchIndexDescriptor index, UUID jobUuid) {
		IndexJob job = jobs.find(jobUuid);
		if (job == null || !job.getIndexId().equals(index.id())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND,
				"No job " + jobUuid + " for the index " + index.id() + ".");
		}
		return job;
	}

	private IndexJobAction parseAction(IndexJobCreateRequest request) {
		String action = request == null ? null : request.getAction();
		if (action == null || action.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"An action is required. One of: " + List.of(IndexJobAction.values()) + ".");
		}
		try {
			return IndexJobAction.valueOf(action.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"Unknown action '" + action + "'. One of: " + List.of(IndexJobAction.values()) + ".");
		}
	}

	private SearchIndexResponse toResponse(SearchIndexDescriptor descriptor) {
		List<String> actions = new ArrayList<>();
		descriptor.supportedActions().forEach(action -> actions.add(action.name()));
		IndexJob active = jobs.active(descriptor.id());
		return new SearchIndexResponse()
			.setId(descriptor.id())
			.setKind(descriptor.kind().name())
			.setBackendId(descriptor.backendId())
			.setLabel(descriptor.label())
			.setType(descriptor.type())
			.setModel(descriptor.model())
			.setDimensions(descriptor.dimensions())
			.setAlgorithm(descriptor.algorithm())
			.setEnabled(descriptor.enabled())
			.setAvailable(descriptor.available())
			.setReason(descriptor.reason())
			.setDocumentCount(descriptor.documentCount())
			.setIndexedCount(descriptor.indexedCount())
			.setPendingCount(descriptor.pendingCount())
			.setLastSyncedAt(descriptor.lastSyncedAt())
			.setSupportedActions(actions)
			.setActiveJob(active == null ? null : toResponse(active));
	}

	private SearchIndexBackendResponse toResponse(SearchIndexBackend backend) {
		return new SearchIndexBackendResponse()
			.setId(backend.id())
			.setProvider(backend.provider())
			.setEnabled(backend.enabled())
			.setAvailable(backend.available())
			.setReason(backend.reason())
			.setDocumentCount(backend.documentCount())
			.setDeletedCount(backend.deletedCount())
			.setSizeBytes(backend.sizeBytes());
	}

	private IndexJobResponse toResponse(IndexJob job) {
		return new IndexJobResponse()
			.setUuid(job.getUuid())
			.setIndexId(job.getIndexId())
			.setAction(job.getAction().name())
			.setState(job.getState().name())
			.setProcessed(job.getProcessed())
			.setTotal(job.getTotal())
			.setRemoved(job.getRemoved())
			.setStartedAt(job.getStartedAt())
			.setFinishedAt(job.getFinishedAt())
			.setError(job.getError());
	}

	/** The configured fingerprint algorithm, for callers that need to name it in a message. */
	public String defaultAlgorithm() {
		return runner.defaultAlgorithm();
	}
}
