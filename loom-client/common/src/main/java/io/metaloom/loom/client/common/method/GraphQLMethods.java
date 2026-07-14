package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;

public interface GraphQLMethods {

	/**
	 * Execute a GraphQL query
	 * 
	 * @param request the GraphQL request containing query, variables, and operationName
	 * @return request that can be executed synchronously or asynchronously
	 */
	LoomClientRequest<GraphQLResponse> executeGraphQL(GraphQLRequest request);

}