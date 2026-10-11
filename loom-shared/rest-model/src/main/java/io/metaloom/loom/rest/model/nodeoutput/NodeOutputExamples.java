package io.metaloom.loom.rest.model.nodeoutput;

import io.metaloom.loom.rest.model.asset.AssetExamples;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface NodeOutputExamples extends AssetExamples {

	/** A reuse key as the node computes it: a lowercase hex SHA-256. Fixed so the published examples do not churn. */
	String EXAMPLE_REUSE_KEY = "9f2c1e7a4b3d58c0e6a1f4d2b7c9e0a3158d6f2b4c7e9a0d3f5b8c1e2a4d6f70";

	default Example nodeOutputCreateRequestExample() {
		return new ExampleImpl(nodeOutputCreateRequest(), "Store what a node emitted, for reuse", HttpResponseStatus.CREATED);
	}

	default Example nodeOutputResponseExample() {
		return new ExampleImpl(nodeOutputResponse(), "The stored node output", HttpResponseStatus.OK);
	}

	default Example nodeOutputListResponseExample() {
		return new ExampleImpl(nodeOutputListResponse(), "The stored node outputs of an asset", HttpResponseStatus.OK);
	}

	default Example nodeOutputLookupResponseExample() {
		return new ExampleImpl(nodeOutputLookupResponse(), "The asset and, on a hit, the stored output", HttpResponseStatus.OK);
	}

	default JsonObject nodeOutputPorts() {
		return new JsonObject().put("transcript", new JsonObject()
			.put("contentType", "text/transcript")
			.put("cardinality", "ONE")
			.put("values", new JsonArray().add("{\"segments\":[{\"from\":0,\"to\":2400,\"text\":\"Hello and welcome.\"}]}")));
	}

	default NodeOutputCreateRequest nodeOutputCreateRequest() {
		return new NodeOutputCreateRequest()
			.setNodeKind("whisper")
			.setReuseKey(EXAMPLE_REUSE_KEY)
			.setNodeId("transcribe")
			.setProducerVersion("ggml-base")
			.setState("SUCCESS")
			.setOutputs(nodeOutputPorts())
			.setRunUuid(uuidA().toString());
	}

	default NodeOutputResponse nodeOutputResponse() {
		return new NodeOutputResponse()
			.setUuid(uuidC().toString())
			.setAssetUuid(uuidA().toString())
			.setNodeKind("whisper")
			.setReuseKey(EXAMPLE_REUSE_KEY)
			.setNodeId("transcribe")
			.setProducerVersion("ggml-base")
			.setState("SUCCESS")
			.setOutputs(nodeOutputPorts())
			.setRunUuid(uuidA().toString())
			.setCreated("2026-10-11T09:30:00Z")
			.setEdited("2026-10-11T09:30:00Z");
	}

	default NodeOutputListResponse nodeOutputListResponse() {
		NodeOutputListResponse model = new NodeOutputListResponse();
		model.setMetainfo(pagingInfo());
		model.add(nodeOutputResponse());
		return model;
	}

	default NodeOutputLookupResponse nodeOutputLookupResponse() {
		return new NodeOutputLookupResponse()
			.setAsset(assetResponse())
			.setOutput(nodeOutputResponse());
	}

}
