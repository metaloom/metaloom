package io.metaloom.loom.rest.model.graphql;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;
import io.vertx.core.json.JsonObject;

public class GraphQLResponse implements RestResponseModel<GraphQLResponse> {

	@JsonPropertyDescription("The GraphQL response data")
	private JsonObject data;

	@JsonPropertyDescription("GraphQL errors if any occurred")
	private JsonObject errors;

	public GraphQLResponse() {
	}

	public GraphQLResponse(JsonObject data, JsonObject errors) {
		this.data = data;
		this.errors = errors;
	}

	public JsonObject getData() {
		return data;
	}

	public void setData(JsonObject data) {
		this.data = data;
	}

	public JsonObject getErrors() {
		return errors;
	}

	public void setErrors(JsonObject errors) {
		this.errors = errors;
	}

	@Override
	public GraphQLResponse self() {
		return this;
	}

}