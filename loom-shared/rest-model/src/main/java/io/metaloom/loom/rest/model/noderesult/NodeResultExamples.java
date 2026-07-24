package io.metaloom.loom.rest.model.noderesult;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface NodeResultExamples extends ExampleValues {

	default Example nodeResultCreateRequestExample() {
		return new ExampleImpl(nodeResultCreateRequest(), "The node result create request", HttpResponseStatus.CREATED);
	}

	default Example nodeResultResponseExample() {
		return new ExampleImpl(nodeResultResponse(), "The node result response", HttpResponseStatus.OK);
	}

	default Example nodeResultListResponseExample() {
		return new ExampleImpl(nodeResultListResponse(), "The node result list response", HttpResponseStatus.OK);
	}

	default NodeResultResponse nodeResultResponse() {
		NodeResultResponse model = new NodeResultResponse();
		model.setUuid(uuidC());
		model.setAssetUuid(uuidA().toString());
		model.setNodeKind("whisper");
		model.setNodeId("");
		model.setProducerVersion("ggml-base");
		model.setState("SUCCESS");
		model.setOrigin("COMPUTED");
		model.setDurationMs(4200L);
		model.setResultRef(new JsonObject()
			.put("table", "asset_transcript_comp")
			.put("uuids", new JsonArray().add(uuidB().toString())));
		setCreatorEditor(model);
		return model;
	}

	default NodeResultCreateRequest nodeResultCreateRequest() {
		NodeResultCreateRequest model = new NodeResultCreateRequest();
		model.setNodeKind("whisper");
		model.setNodeId("");
		model.setProducerVersion("ggml-base");
		model.setState("SUCCESS");
		model.setOrigin("COMPUTED");
		model.setDurationMs(4200L);
		model.setResultRef(new JsonObject()
			.put("table", "asset_transcript_comp")
			.put("uuids", new JsonArray().add(uuidB().toString())));
		return model;
	}

	default NodeResultListResponse nodeResultListResponse() {
		NodeResultListResponse model = new NodeResultListResponse();
		model.setMetainfo(pagingInfo());
		model.add(nodeResultResponse());
		return model;
	}

}
