package io.metaloom.loom.rest.model.pipeline;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonObject;

public interface PipelineExamples extends ExampleValues {

	default Example pipelineUpdateRequestExample() {
		return new ExampleImpl(pipelineUpdateRequest(), "The pipeline update request", HttpResponseStatus.OK);
	}

	default Example pipelineCreateRequestExample() {
		return new ExampleImpl(pipelineCreateRequest(), "The pipeline create request", HttpResponseStatus.CREATED);
	}

	default Example pipelineResponseExample() {
		return new ExampleImpl(pipelineResponse(), "The pipeline response", HttpResponseStatus.OK);
	}

	default Example pipelineListResponseExample() {
		return new ExampleImpl(pipelineListResponse(), "The pipeline list response", HttpResponseStatus.OK);
	}

	default PipelineResponse pipelineResponse() {
		PipelineResponse model = new PipelineResponse();
		model.setUuid(uuidC());
		model.setName("my-pipeline");
		model.setDescription("A sample pipeline");
		model.setDefinition(new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()));
		model.setEnabled(true);
		model.setPriority(10);
		model.setDryRun(false);
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default PipelineUpdateRequest pipelineUpdateRequest() {
		PipelineUpdateRequest model = new PipelineUpdateRequest();
		model.setName("updated-pipeline");
		model.setDescription("An updated pipeline");
		model.setEnabled(false);
		model.setMeta(meta());
		return model;
	}

	default PipelineCreateRequest pipelineCreateRequest() {
		PipelineCreateRequest model = new PipelineCreateRequest();
		model.setName("my-pipeline");
		model.setDescription("A new pipeline");
		model.setDefinition(new JsonObject().put("nodes", new io.vertx.core.json.JsonArray()));
		model.setEnabled(true);
		model.setPriority(10);
		model.setDryRun(false);
		model.setMeta(meta());
		return model;
	}

	default PipelineListResponse pipelineListResponse() {
		PipelineListResponse model = new PipelineListResponse();
		model.setMetainfo(pagingInfo());
		model.add(pipelineResponse());
		model.add(pipelineResponse());
		return model;
	}

}
