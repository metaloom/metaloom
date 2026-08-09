package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.searchindex.IndexJobCreateRequest;
import io.metaloom.loom.rest.model.searchindex.IndexJobListResponse;
import io.metaloom.loom.rest.model.searchindex.IndexJobResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexListResponse;
import io.metaloom.loom.rest.model.searchindex.SearchIndexResponse;

/**
 * Client access to the search index admin routes.
 *
 * <p>
 * Covers every index the instance maintains - lexical documents, each embedding vector space and the duplicate fingerprints - and supersedes the
 * older per-backend {@link SimilarityMethods#rebuildSimilarityIndex()}, which rebuilt synchronously and reported nothing while it ran.
 * </p>
 */
public interface SearchIndexMethods {

	/** List every search index with its state, model, backlog and the storage backends they live in. */
	LoomClientRequest<SearchIndexListResponse> listSearchIndices();

	/**
	 * Read one index.
	 *
	 * @param indexId
	 *            an id from {@link #listSearchIndices()}, e.g. {@code lexical} or {@code vector-face-inspireface-r18-512}. Do not construct these -
	 *            they are slugs of a model name and are resolved by lookup, not by parsing.
	 */
	LoomClientRequest<SearchIndexResponse> loadSearchIndex(String indexId);

	/** Recent maintenance jobs for one index, newest first. Includes the running one. */
	LoomClientRequest<IndexJobListResponse> listSearchIndexJobs(String indexId);

	/**
	 * Start a maintenance job. Answers 202 with the job; poll {@link #loadSearchIndexJob(String, UUID)} for progress.
	 *
	 * @param request
	 *            the action: {@code REINDEX}, {@code DELTA_SYNC} or {@code DROP}, which must be one of the index's {@code supportedActions}
	 */
	LoomClientRequest<IndexJobResponse> createSearchIndexJob(String indexId, IndexJobCreateRequest request);

	/** Read one job and how far it has got. */
	LoomClientRequest<IndexJobResponse> loadSearchIndexJob(String indexId, UUID jobUuid);

	/** Ask a running job to stop at its next item boundary. Returns the job as it was at the moment of asking. */
	LoomClientRequest<IndexJobResponse> cancelSearchIndexJob(String indexId, UUID jobUuid);
}
