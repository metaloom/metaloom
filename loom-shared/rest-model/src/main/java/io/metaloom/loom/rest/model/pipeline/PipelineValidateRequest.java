package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

/**
 * Ask whether a definition would be accepted, without storing it.
 *
 * <p>
 * The definition is wrapped in a request object rather than posted bare so the typed clients have something to build and so the route can grow a flag
 * later without becoming a breaking change. The field is the same {@code definition} that {@link PipelineCreateRequest} carries — deliberately, since
 * the whole value of the route is that a draft which validates here is a draft that saves.
 * </p>
 */
public class PipelineValidateRequest implements RestRequestModel {

	@JsonPropertyDescription("The pipeline definition to check: {version, nodes[], edges[]}.")
	private JsonObject definition;

	public PipelineValidateRequest() {
	}

	public JsonObject getDefinition() {
		return definition;
	}

	public PipelineValidateRequest setDefinition(JsonObject definition) {
		this.definition = definition;
		return this;
	}
}
