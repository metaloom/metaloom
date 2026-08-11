package io.metaloom.loom.client.http;

import java.time.Duration;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.common.method.ClientMethods;
import io.metaloom.loom.client.http.impl.LoomHttpClientImpl;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;
import io.metaloom.loom.rest.model.health.HealthCheckResponse;
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
	 * Return the share session token, when this client is being used to read a customer-facing share link.
	 *
	 * <p>
	 * A second, entirely separate credential from {@link #getToken()}. A share visitor is not a user: the token proves only that a link's password was
	 * satisfied, and it is sent as {@code X-Loom-Share-Session} rather than as a bearer token precisely so that it can never be mistaken for one. Both
	 * may be set on the same client without interfering - which is what lets an integration test drive the owner side and the customer side of the
	 * same link.
	 * </p>
	 *
	 * @return the token, or null when this client is not holding a share session
	 */
	String getShareSessionToken();

	/**
	 * Set the share session token returned by {@code POST /shares/{slug}/sessions}.
	 *
	 * @param token
	 *            the opaque session token, or null to clear it
	 */
	void setShareSessionToken(String token);

	/**
	 * Execute a GraphQL query
	 * 
	 * @param request the GraphQL request containing query, variables, and operationName
	 * @return request that can be executed synchronously or asynchronously
	 */
	LoomClientRequest<GraphQLResponse> executeGraphQL(GraphQLRequest request);

	/**
	 * Health check endpoint
	 * 
	 * @return request that can be executed synchronously or asynchronously
	 */
	LoomClientRequest<HealthCheckResponse> health();

}
