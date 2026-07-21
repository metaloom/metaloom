package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.POST;

import java.util.Map;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import io.metaloom.loom.graphql.GraphQLPermissionChecker;
import io.metaloom.loom.graphql.LoomGraphQLProvider;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.vertx.core.json.JsonObject;

/**
 * REST endpoint that exposes the GraphQL API at {@code /api/v1/graphql}.
 *
 * <p>Accepts POST requests with a JSON body containing a {@code query} field and optional {@code variables} and {@code operationName} fields.</p>
 */
public class GraphQLEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(GraphQLEndpoint.class);

	private final LoomGraphQLProvider graphQLProvider;

	@Inject
	public GraphQLEndpoint(EndpointDependencies deps, LoomGraphQLProvider graphQLProvider) {
		super(deps);
		this.graphQLProvider = graphQLProvider;
	}

	@Override
	public String name() {
		return "graphql";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/graphql";
	}

	@Override
	public void register() {
		log.info("Registering GraphQL endpoint");
		// Require authentication (JWT / OAuth2) for the GraphQL endpoint, reusing the same handler as the REST API.
		secure(basePath());
		addRoute(basePath(), POST, "Execute a GraphQL query", lrc -> {
			handleGraphQL(lrc);
		});
	}

	private void handleGraphQL(LoomRoutingContext lrc) {
		// Resolve the authenticated user's permissions first so field level checks can run synchronously during
		// GraphQL execution, then hand a permission checker to the data fetchers via the execution context.
		lrc.permissionChecker()
			.onSuccess(checker -> executeGraphQL(lrc, checker))
			.onFailure(e -> {
				log.error("Failed to resolve permissions for GraphQL request", e);
				lrc.error("Failed to resolve permissions: " + e.getMessage());
			});
	}

	private void executeGraphQL(LoomRoutingContext lrc, GraphQLPermissionChecker checker) {
		try {
			JsonObject body = lrc.bodyAsJson();

			String query = body.getString("query");
			String operationName = body.getString("operationName");
			Map<String, Object> variables = null;
			if (body.getJsonObject("variables") != null) {
				variables = body.getJsonObject("variables").getMap();
			}

			ExecutionInput.Builder builder = ExecutionInput.newExecutionInput()
				.query(query)
				.graphQLContext(Map.of(GraphQLPermissionChecker.CONTEXT_KEY, checker));
			if (operationName != null) {
				builder.operationName(operationName);
			}
			if (variables != null) {
				builder.variables(variables);
			}

			ExecutionResult result = graphQLProvider.graphQL().execute(builder.build());
			Map<String, Object> resultMap = result.toSpecification();

			lrc.sendText(
				JsonObject.mapFrom(resultMap).encode(),
				"application/json",
				200);
		} catch (Exception e) {
			log.error("GraphQL execution failed", e);
			lrc.error("GraphQL execution failed: " + e.getMessage());
		}
	}
}
