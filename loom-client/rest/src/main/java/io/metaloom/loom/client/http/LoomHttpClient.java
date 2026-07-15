package io.metaloom.loom.client.http;

import java.time.Duration;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.common.method.ClientMethods;
import io.metaloom.loom.client.http.impl.LoomHttpClientImpl;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;

public interface LoomHttpClient extends ClientSettings, LoomClient {

	static LoomHttpClientImpl.Builder builder() {
		return LoomHttpClientImpl.builder();
	}

	String API_V1_PATH = "/api/v1";

	/**
	 * Return the configured protocol scheme.
	 *
	 * @return
	 */
	String getScheme();

	/**
	 * Return the configured server hostname.
	 *
	 * @return
	 */
	String getHostname();

	/**
	 * Return the configured server port.
	 *
	 * @return
	 */
	int getPort();

	/**
	 * Return the configured prefix for the API calls.
	 * 
	 * @return
	 */
	String getPathPrefix();

	/**
	 * Return the configured connect timeout.
	 *
	 * @return
	 */
	Duration getConnectTimeout();

	/**
	 * Return the configured read timeout.
	 *
	 * @return
	 */
	Duration getReadTimeout();

	/**
	 * Return the configured write timeout.
	 *
	 * @return
	 */
	Duration getWriteTimeout();

	/**
	 * Return the used authentication token.
	 *
	 * @return
	 */
	String getToken();

	/**
	 * Execute a GraphQL query
	 * 
	 * @param request the GraphQL request containing query, variables, and operationName
	 * @return request that can be executed synchronously or asynchronously
	 */
	LoomClientRequest<GraphQLResponse> executeGraphQL(GraphQLRequest request);

}
