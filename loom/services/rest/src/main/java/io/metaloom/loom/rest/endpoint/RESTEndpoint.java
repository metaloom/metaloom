package io.metaloom.loom.rest.endpoint;

public interface RESTEndpoint {

	/**
	 * Name of the endpoint
	 * 
	 * @return
	 */
	String name();

	/**
	 * Register the endpoint routing handlers.
	 */
	void register();

	/**
	 * Return the base path for the endpoints. The basepath of the endpoint should be prefixed with the REST API version path.
	 * 
	 * @return
	 */
	String basePath();
}
