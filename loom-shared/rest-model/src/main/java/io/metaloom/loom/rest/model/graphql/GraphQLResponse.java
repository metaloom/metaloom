package io.metaloom.loom.rest.model.graphql;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class GraphQLResponse implements RestResponseModel<GraphQLResponse> {

	@JsonPropertyDescription("The GraphQL response data")
	private JsonObject data;

	/**
	 * GraphQL errors are a JSON array of error objects (per the GraphQL spec), each potentially carrying an
	 * {@code extensions} object (e.g. {@code code: FORBIDDEN} for authorization failures).
	 */
	@JsonPropertyDescription("GraphQL errors if any occurred")
	private JsonArray errors;

	public GraphQLResponse() {
	}

	public GraphQLResponse(JsonObject data, JsonArray errors) {
		this.data = data;
		this.errors = errors;
	}

	public JsonObject getData() {
		return data;
	}

	public void setData(JsonObject data) {
		this.data = data;
	}

	public JsonArray getErrors() {
		return errors;
	}

	public void setErrors(JsonArray errors) {
		this.errors = errors;
	}

	@Override
	public GraphQLResponse self() {
		return this;
	}

}