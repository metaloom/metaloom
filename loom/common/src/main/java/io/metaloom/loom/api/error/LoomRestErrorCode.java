package io.metaloom.loom.api.error;

/**
 * Error codes for Loom REST API errors.
 */
public enum LoomRestErrorCode {

	BAD_PATH_PARAMS,
	BAD_QUERY_PARAMS,
	BAD_REQUEST,
	BAD_FILTER_KEY,
	CONFLICT,
	INTERNAL_ERROR,
	MISSING_PERM,
	NOT_FOUND,

	/**
	 * No search provider is configured or the configured one failed to start. Mapped to HTTP 503 - the request was well formed, the capability is simply
	 * not available on this deployment.
	 */
	SEARCH_UNAVAILABLE,

	/**
	 * The search request asked for a capability the bound provider does not advertise, e.g. semantic mode on the Postgres provider or an offset past the
	 * provider's deep-paging cap. Mapped to HTTP 400.
	 */
	SEARCH_UNSUPPORTED,

	UPLOAD_DATA_MISSING;

}
