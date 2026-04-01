package io.metaloom.loom.api.error;

/**
 * Error codes for Loom REST API errors.
 */
public enum LoomRestErrorCode {

	BAD_PATH_PARAMS,
	BAD_QUERY_PARAMS,
	BAD_REQUEST,
	BAD_FILTER_KEY,
	INTERNAL_ERROR,
	MISSING_PERM,
	NOT_FOUND,
	UPLOAD_DATA_MISSING;

}
