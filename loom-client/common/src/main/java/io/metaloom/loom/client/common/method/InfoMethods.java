package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.info.RESTInfoResponse;
import io.metaloom.loom.rest.model.user.UserResponse;

public interface InfoMethods {

	/**
	 * Load the API root ({@code GET /api/v1}) which reports the server version and the
	 * database revision it is running against.
	 */
	LoomClientRequest<RESTInfoResponse> restInfo();

	/**
	 * Load the user the current token belongs to ({@code GET /api/v1/me}).
	 */
	LoomClientRequest<UserResponse> me();
}
