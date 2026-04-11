package io.metaloom.loom.client.common;

import java.util.List;
import java.util.Map;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Wrapper for a client response that provides access to both the deserialized model/payload and the underlying HTTP response metadata (status code, headers,
 * etc.).
 *
 * @param <T>
 *            The response model type
 */
public interface LoomClientResponse<T extends RestResponseModel<T>> {

	/**
	 * Return the deserialized response body (model/payload).
	 *
	 * @return The response model, or {@code null} for no-content responses
	 */
	T body();

	/**
	 * Return the HTTP status code.
	 *
	 * @return
	 */
	int statusCode();

	/**
	 * Return the HTTP status message.
	 *
	 * @return
	 */
	String statusMessage();

	/**
	 * Return all response headers as a map of header name to list of values.
	 *
	 * @return
	 */
	Map<String, List<String>> headers();

	/**
	 * Return the first value for the given header name, or {@code null} if not present.
	 *
	 * @param name
	 * @return
	 */
	String header(String name);

	/**
	 * Return all values for the given header name.
	 *
	 * @param name
	 * @return
	 */
	List<String> headers(String name);

}
