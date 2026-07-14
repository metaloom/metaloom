package io.metaloom.loom.rest.model.graphql;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

public class GraphQLRequest implements RestRequestModel {

	@JsonPropertyDescription("The GraphQL query string")
	private String query;

	@JsonPropertyDescription("Optional operation name for the query")
	private String operationName;

	@JsonPropertyDescription("Optional variables for the query")
	private JsonObject variables;

	public GraphQLRequest() {
	}

	public GraphQLRequest(String query) {
		this.query = query;
	}

	public GraphQLRequest(String query, JsonObject variables) {
		this.query = query;
		this.variables = variables;
	}

	public GraphQLRequest(String query, String operationName, JsonObject variables) {
		this.query = query;
		this.operationName = operationName;
		this.variables = variables;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public String getOperationName() {
		return operationName;
	}

	public void setOperationName(String operationName) {
		this.operationName = operationName;
	}

	public JsonObject getVariables() {
		return variables;
	}

	public void setVariables(JsonObject variables) {
		this.variables = variables;
	}

}