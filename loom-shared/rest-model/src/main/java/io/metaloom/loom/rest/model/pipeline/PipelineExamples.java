package io.metaloom.loom.rest.model.pipeline;

import java.util.List;
import java.util.UUID;

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

	default Example pipelineRunRequestExample() {
		return new ExampleImpl(pipelineRunRequest(), "The pipeline run request", HttpResponseStatus.ACCEPTED);
	}

	default Example pipelineRunResponseExample() {
		return new ExampleImpl(pipelineRunResponse(), "The pipeline run response", HttpResponseStatus.ACCEPTED);
	}

	default Example pipelineRunListResponseExample() {
		return new ExampleImpl(pipelineRunListResponse(), "The pipeline run list response", HttpResponseStatus.OK);
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

	default PipelineRunRequest pipelineRunRequest() {
		PipelineRunRequest model = new PipelineRunRequest();
		model.setMediaUuids(List.of(uuidA(), uuidB()));
		model.setDryRun(false);
		return model;
	}

	default PipelineRunResponse pipelineRunResponse() {
		PipelineRunResponse model = new PipelineRunResponse();
		model.setWorkOrderId(UUID.randomUUID());
		model.setProcessorNodeId("cortex-01");
		model.setDispatched(true);
		model.setMessage("Work order dispatched");
		return model;
	}

	default PipelineRunListResponse pipelineRunListResponse() {
		PipelineRunListResponse model = new PipelineRunListResponse();
		model.add(pipelineRunRecord());
		model.add(pipelineRunRecord());
		model.getMetainfo().setTotalCount(2);
		model.getMetainfo().setPerPage(30L);
		return model;
	}

	default PipelineRunRecord pipelineRunRecord() {
		PipelineRunRecord model = new PipelineRunRecord();
		model.setUuid(UUID.randomUUID());
		model.setPipelineUuid(UUID.randomUUID());
		model.setPipelineVersion(1);
		model.setStarted(java.time.Instant.now().toString());
		model.setFinished(java.time.Instant.now().toString());
		model.setStatus("SUCCESS");
		model.setMediaCount(100);
		model.setSuccessCount(95);
		model.setFailureCount(3);
		model.setSkippedCount(2);
		model.setDryRun(false);
		model.setDurationMs(45000L);
		return model;
	}

}
